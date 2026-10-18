#!/usr/bin/env bash
# EC2에서 서버 컨테이너를 새 이미지 태그로 교체한다. 새 버전의 헬스체크가 실패하면 직전 정상 태그로 되돌린다.
#
# 사용법:  sudo bash deploy.sh <이미지 태그>
#   예)    sudo bash deploy.sh 3f2a9c1
#
# 순서가 중요하다.
#   1. 설정 검증: Parameter Store의 값이 제대로 있는지 확인한다. 기존 컨테이너를 내리기 전에 검증해서, 설정 오류로 서비스가 끊기지 않게 한다.
#   2. 이미지 받기: ECR에서 새 이미지를 받는다. 이 동안 기존 컨테이너는 계속 서비스한다.
#   3. 교체: 기존 컨테이너를 정상 종료하고 새 컨테이너를 띄운다.
#   4. 판정: 헬스체크가 UP이면 이 태그를 "직전 정상 태그"로 기록한다. 실패하면 기록해 둔 직전 정상 태그로 되돌린다.
#
# 종료 코드: 0 = 배포 성공, 그 외 = 실패(되돌리기에 성공했더라도 이번 배포는 실패로 처리한다).
# 전제: docker, aws CLI, curl이 있고, 인스턴스 역할이 ECR 읽기와 Parameter Store(/blackhole) 읽기 권한을 가진다.
#       SSM Run Command(root) 또는 sudo로 실행하는 것을 가정한다.

set -euo pipefail

# --- 설정 (환경변수로 덮어쓸 수 있다) ---
REGION="${REGION:-ap-northeast-2}"
PARAM_PATH="${PARAM_PATH:-/blackhole}"
REPO="${REPO:-feedablackhole-server}"
NAME="${NAME:-blackhole}"
HOST_PORT="${HOST_PORT:-8080}"
STATE_DIR="${STATE_DIR:-/var/lib/blackhole}"
LOCK_FILE="${LOCK_FILE:-/var/lock/blackhole-deploy.lock}"
# docker stop의 기본 대기(10초)는 앱의 graceful shutdown 대기(20초)보다 짧아서, 처리 중인 요청이 강제 종료될 수 있다.
STOP_TIMEOUT="${STOP_TIMEOUT:-30}"
# DB 연결이 끊긴 상태에서는 헬스체크 응답이 약 30초 걸리므로 요청 하나의 제한은 40초로 한다.
HEALTH_REQUEST_TIMEOUT="${HEALTH_REQUEST_TIMEOUT:-40}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"
POLL_INTERVAL="${POLL_INTERVAL:-3}"

LAST_GOOD_FILE="$STATE_DIR/last-good-tag"

log() { printf '%s [deploy] %s\n' "$(date '+%H:%M:%S')" "$*"; }
die() { log "오류: $*" >&2; exit 1; }

TAG="${1:-}"
[[ -n $TAG ]] || die "사용법: deploy.sh <이미지 태그>"
[[ $TAG =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || die "태그 형식이 올바르지 않습니다: $TAG"

# 동시에 두 번 실행되지 않게 한다.
exec 9>"$LOCK_FILE"
flock -n 9 || die "다른 배포가 진행 중입니다."

# 계정 ID를 저장소에 적지 않도록 실행 중에 조회한다.
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text --region "$REGION")"
REGISTRY="${REGISTRY:-$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com}"
IMAGE="$REGISTRY/$REPO:$TAG"

# ECR 로그인 정보는 임시 폴더에 두고, 끝나면 폴더째 지운다(서버에 토큰이 남지 않는다).
# 비밀값을 담는 env 파일은 mktemp로 만들어 소유자만 읽을 수 있고, 종료할 때 반드시 지운다.
umask 077
DOCKER_CONFIG="$(mktemp -d)"
export DOCKER_CONFIG
ENV_FILE="$(mktemp)"
trap 'rm -rf "$DOCKER_CONFIG" "$ENV_FILE"' EXIT

# --- 1. 설정 검증 ---
log "Parameter Store($PARAM_PATH)에서 설정을 읽습니다."
aws ssm get-parameters-by-path --path "$PARAM_PATH" --with-decryption --region "$REGION" \
    --query "Parameters[].[Name,Value]" --output text \
    | while IFS=$'\t' read -r name value; do printf '%s=%s\n' "${name##*/}" "$value"; done > "$ENV_FILE" \
    || die "Parameter Store 조회에 실패했습니다."

for key in DB_URL DB_USERNAME DB_PASSWORD JWT_SECRET; do
    grep -q "^${key}=." "$ENV_FILE" || die "파라미터 $PARAM_PATH/$key 가 없거나 비어 있습니다."
done
jwt_secret="$(grep '^JWT_SECRET=' "$ENV_FILE" | cut -d= -f2-)"
(( ${#jwt_secret} >= 32 )) || die "JWT_SECRET이 32자 미만입니다. 서버가 시작하지 않습니다."
unset jwt_secret

# --- 2. 이미지 받기 ---
log "ECR에서 이미지를 받습니다: $IMAGE"
aws ecr get-login-password --region "$REGION" \
    | docker login --username AWS --password-stdin "$REGISTRY" > /dev/null 2>&1 \
    || die "ECR 로그인에 실패했습니다."
docker pull "$IMAGE" > /dev/null || die "이미지를 받지 못했습니다: $IMAGE"

# 되돌릴 대상: 마지막으로 헬스체크를 통과한 태그. 기록이 없으면 지금 실행 중인 컨테이너의 태그.
mkdir -p "$STATE_DIR"
PREV_TAG=""
if [[ -s $LAST_GOOD_FILE ]]; then
    PREV_TAG="$(< "$LAST_GOOD_FILE")"
elif docker inspect "$NAME" > /dev/null 2>&1; then
    running_image="$(docker inspect -f '{{.Config.Image}}' "$NAME")"
    PREV_TAG="${running_image##*:}"
fi

remove_container() {
    if docker inspect "$NAME" > /dev/null 2>&1; then
        docker stop -t "$STOP_TIMEOUT" "$NAME" > /dev/null || true
        docker rm "$NAME" > /dev/null || true
    fi
}

# 앱 포트는 서버 내부(127.0.0.1)에만 연다. 외부에서는 Nginx만 접근한다.
start_container() {
    docker run -d --name "$NAME" --restart unless-stopped \
        -p "127.0.0.1:$HOST_PORT:8080" --env-file "$ENV_FILE" "$1" > /dev/null
}

wait_healthy() {
    local deadline=$(( SECONDS + HEALTH_TIMEOUT ))
    local state response code body
    while (( SECONDS < deadline )); do
        state="$(docker inspect -f '{{.State.Status}}' "$NAME" 2> /dev/null || echo missing)"
        if [[ $state != running ]]; then
            log "컨테이너 상태가 running이 아닙니다: $state"
            return 1
        fi
        response="$(curl -s --max-time "$HEALTH_REQUEST_TIMEOUT" -w '\n%{http_code}' \
            "http://127.0.0.1:$HOST_PORT/actuator/health" || true)"
        code="$(tail -n 1 <<< "$response")"
        body="$(head -n -1 <<< "$response")"
        if [[ $code == 200 && $body == *'"status":"UP"'* ]]; then
            return 0
        fi
        sleep "$POLL_INTERVAL"
    done
    log "헬스체크 제한 시간(${HEALTH_TIMEOUT}초)을 넘었습니다."
    return 1
}

# --- 3. 교체 ---
log "교체를 시작합니다: ${PREV_TAG:-없음} -> $TAG"
remove_container

# --- 4. 판정 ---
if start_container "$IMAGE" && wait_healthy; then
    echo "$TAG" > "$LAST_GOOD_FILE"
    log "배포 성공: $TAG"
    # 7일 넘게 쓰지 않은 이미지는 지워 디스크를 지킨다. 되돌릴 직전 이미지는 남는다.
    docker image prune -af --filter "until=168h" > /dev/null 2>&1 || true
    exit 0
fi

log "배포 실패: $TAG. 컨테이너 로그(마지막 40줄):"
docker logs --tail 40 "$NAME" 2>&1 | sed 's/^/    /' || true
remove_container

if [[ -n $PREV_TAG && $PREV_TAG != "$TAG" ]]; then
    PREV_IMAGE="$REGISTRY/$REPO:$PREV_TAG"
    log "직전 정상 태그로 되돌립니다: $PREV_TAG"
    docker image inspect "$PREV_IMAGE" > /dev/null 2>&1 \
        || docker pull "$PREV_IMAGE" > /dev/null \
        || die "되돌릴 이미지를 받지 못했습니다: $PREV_IMAGE"
    if start_container "$PREV_IMAGE" && wait_healthy; then
        log "되돌리기 성공: $PREV_TAG (이번 배포는 실패로 처리합니다)"
    else
        die "되돌리기도 실패했습니다. 수동 점검이 필요합니다."
    fi
else
    log "되돌릴 이전 정상 태그가 없습니다. 서비스가 내려간 상태입니다."
fi
exit 1
