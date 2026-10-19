#!/usr/bin/env bash
# 문서·설정에 인프라 식별 정보(AWS 계정 ID, 엔드포인트, 리소스 ID, 공인 IP 등)가 들어가지 않도록 검사한다.
# 이 저장소는 공개라서 실제 값 대신 <계정ID>, <RDS 엔드포인트>, <탄력적 IP> 같은 자리표시자를 쓴다.
#
# 사용법:
#   bash scripts/check-identifiers.sh              Git이 추적하는 모든 파일을 검사한다(CI에서 사용).
#   bash scripts/check-identifiers.sh --staged     스테이징된 내용만 검사한다(커밋 전에 직접 확인할 때).
#   bash scripts/check-identifiers.sh --self-test  검사 규칙이 의도대로 동작하는지 예시 문장으로 확인한다.
#
# 공개해도 되는 값이면 해당 줄에 "identifier-check: allow"를 적어 제외한다.
# CI 로그도 공개이므로, 발견한 값 자체는 출력하지 않고 파일·줄 번호·규칙 이름만 출력한다.
#
# 종료 코드: 0 = 문제 없음, 1 = 식별 정보로 의심되는 값 발견, 2 = 사용법 오류.

set -uo pipefail

ALLOW_MARKER='identifier-check: allow'

# 모든 텍스트 파일에 적용하는 규칙. 규칙 이름과 정규식(ERE)이 같은 순서로 대응한다.
RULE_NAMES=(
    "AWS 계정 ID가 든 ARN"
    "ECR 주소의 계정 ID"
    "RDS 엔드포인트"
    "EC2 퍼블릭 DNS"
    "로드밸런서 주소"
    "AWS 리소스 ID"
    "AWS 액세스 키 ID"
)
RULE_PATTERNS=(
    'arn:aws[a-z-]*:[a-z0-9-]*:[a-z0-9-]*:[0-9]{12}:'
    '[0-9]{12}\.dkr\.ecr'
    '[a-z0-9-]+\.[a-z0-9]{8,}\.[a-z0-9-]+\.rds\.amazonaws\.com'
    'ec2-[0-9]{1,3}(-[0-9]{1,3}){3}\.[a-z0-9-]+\.compute\.amazonaws\.com'
    '[a-z0-9-]+\.[a-z0-9-]+\.elb\.amazonaws\.com'
    '(^|[^A-Za-z0-9_-])(i|sg|vpc|subnet|rtb|igw|eni|vpce|nat|snap|vol|ami|eipalloc|eipassoc|acl)-([0-9a-f]{17}|[0-9a-f]{8})($|[^A-Za-z0-9_-])'
    '(^|[^A-Z0-9])(AKIA|ASIA)[0-9A-Z]{16}($|[^A-Z0-9])'
)

# 문서 파일(.md, .txt, .yml, .yaml)에만 적용하는 규칙. 코드에는 12자리 숫자 상수가 있을 수 있어서 문서로 한정한다.
DOC_RULE_NAME="12자리 숫자(AWS 계정 ID 의심)"
DOC_RULE_PATTERN='(^|[^0-9])[0-9]{12}($|[^0-9])'
IP_RULE_NAME="공인 IPv4 주소"

is_doc_file() {
    case "$1" in
        *.md|*.txt|*.yml|*.yaml) return 0 ;;
        *) return 1 ;;
    esac
}

# 검사 대상에서 뺀다: 이 스크립트(규칙 예시가 들어 있다), 게임 콘텐츠 데이터, Gradle Wrapper
is_excluded() {
    case "$1" in
        scripts/check-identifiers.sh|src/main/resources/content/*|gradle/wrapper/*) return 0 ;;
        *) return 1 ;;
    esac
}

# 줄 번호가 붙은 텍스트(표준입력)에서 공인 IPv4가 있는 줄 번호를 출력한다.
# 사설(10.x, 172.16~31.x, 192.168.x), 루프백, 링크 로컬, 문서용 예약 대역(192.0.2.x, 198.51.100.x, 203.0.113.x)은 제외한다.
public_ipv4_lines() {
    awk '
    {
        sep = index($0, ":"); no = substr($0, 1, sep - 1); s = substr($0, sep + 1); off = 0
        while (match(substr(s, off + 1), /[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/)) {
            abs = off + RSTART; len = RLENGTH; ip = substr(s, abs, len)
            prev = (abs > 1) ? substr(s, abs - 1, 1) : ""
            nxt = substr(s, abs + len, 1); nxt2 = substr(s, abs + len + 1, 1)
            off = abs + len - 1
            if (prev ~ /[0-9.]/) continue
            if (nxt == "." && nxt2 ~ /[0-9]/) continue
            split(ip, o, ".")
            if (o[1] > 255 || o[2] > 255 || o[3] > 255 || o[4] > 255) continue
            if (o[1] == 0 || o[1] == 10 || o[1] == 127 || o[1] >= 224) continue
            if (o[1] == 172 && o[2] >= 16 && o[2] <= 31) continue
            if (o[1] == 192 && o[2] == 168) continue
            if (o[1] == 169 && o[2] == 254) continue
            if (o[1] == 192 && o[2] == 0 && o[3] == 2) continue
            if (o[1] == 198 && o[2] == 51 && o[3] == 100) continue
            if (o[1] == 203 && o[2] == 0 && o[3] == 113) continue
            print no; break
        }
    }'
}

# 표준입력을 검사해 "경로:줄 [규칙]" 형식으로 출력한다. 첫 인자는 표시할 경로, 둘째는 문서 파일 여부(1/0).
scan_stream() {
    local label=$1 is_doc=$2 text i n
    text="$(awk '{ print NR ":" $0 }' | grep -vF "$ALLOW_MARKER")"
    [[ -n $text ]] || return 0
    for i in "${!RULE_NAMES[@]}"; do
        while IFS= read -r n; do
            printf '%s:%s [%s]\n' "$label" "${n%%:*}" "${RULE_NAMES[$i]}"
        done < <(grep -E "${RULE_PATTERNS[$i]}" <<< "$text")
    done
    if [[ $is_doc == 1 ]]; then
        while IFS= read -r n; do
            printf '%s:%s [%s]\n' "$label" "${n%%:*}" "$DOC_RULE_NAME"
        done < <(grep -E "$DOC_RULE_PATTERN" <<< "$text")
    fi
    while IFS= read -r n; do
        printf '%s:%s [%s]\n' "$label" "$n" "$IP_RULE_NAME"
    done < <(public_ipv4_lines <<< "$text")
}

FOUND=0

# 파일 하나를 검사한다. $2는 worktree(작업 폴더의 파일) 또는 staged(스테이징된 내용).
scan_path() {
    local path=$1 mode=$2 doc=0 out
    is_excluded "$path" && return 0
    is_doc_file "$path" && doc=1
    if [[ $mode == staged ]]; then
        git show ":$path" 2> /dev/null | grep -Iq . || return 0    # 바이너리·빈 파일은 건너뛴다
        out="$(git show ":$path" | scan_stream "$path" "$doc")"
    else
        [[ -f $path ]] && grep -Iq . "$path" || return 0
        out="$(scan_stream "$path" "$doc" < "$path")"
    fi
    if [[ -n $out ]]; then
        printf '%s\n' "$out"
        FOUND=1
    fi
}

# 모든 규칙을 한 번에 훑어 의심되는 파일만 고른다. 파일마다 도구를 여러 번 띄우면 Windows에서 매우 느려서,
# 깨끗한 파일은 여기서 걸러 내고 후보만 scan_path로 정밀 검사한다.
candidate_files() {
    local p
    local -a args=()
    for p in "${RULE_PATTERNS[@]}" "$DOC_RULE_PATTERN" '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+'; do
        args+=(-e "$p")
    done
    git grep -lIzE "${args[@]}"
}

run_scan() {
    local mode=$1 path
    cd "$(git rev-parse --show-toplevel)" || exit 2
    if [[ $mode == staged ]]; then
        while IFS= read -r -d '' path; do
            scan_path "$path" staged
        done < <(git diff --cached --name-only -z --diff-filter=ACMR)
    else
        while IFS= read -r -d '' path; do
            scan_path "$path" worktree
        done < <(candidate_files)
    fi
    if [[ $FOUND -ne 0 ]]; then
        echo
        echo "식별 정보로 의심되는 값이 있습니다(공개 로그에 남기지 않으려고 값은 출력하지 않았습니다)."
        echo "- 자리표시자로 바꾸세요: <계정ID>, <RDS 엔드포인트>, <탄력적 IP>, <인스턴스 ID> 등"
        echo "- 공개해도 되는 값이면 해당 줄에 \"$ALLOW_MARKER\" 를 적어 제외하세요."
        exit 1
    fi
    echo "식별 정보로 의심되는 값이 없습니다."
}

# 규칙이 의도대로 동작하는지 예시 문장으로 확인한다. 검사 규칙을 고칠 때 함께 돌려 본다.
self_test() {
    local fail=0
    # 액세스 키 예시는 소스에 그대로 적으면 비밀값 스캐너가 반응하므로 실행 중에 이어 붙인다.
    local key="AKI""AABCDEFGHIJKLMNOP"
    expect() {    # 기대(hit 또는 ok), 문서 파일 여부(1/0), 예시 문장
        local want=$1 doc=$2 text=$3 got
        got="$(printf '%s\n' "$text" | scan_stream sample "$doc")"
        if [[ $want == hit && -n $got ]] || [[ $want == ok && -z $got ]]; then
            return 0
        fi
        printf '자체 점검 실패(%s 기대): %s\n' "$want" "$text"
        fail=1
    }

    # 잡아야 하는 것
    expect hit 0 'role arn:aws:iam::123456789012:role/example'
    expect hit 0 'registry 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/repo'
    expect hit 0 'host mydb.abcdefghijkl.ap-northeast-2.rds.amazonaws.com:3306'
    expect hit 0 'ec2-3-35-10-20.ap-northeast-2.compute.amazonaws.com'
    expect hit 0 'my-lb-1234567890.ap-northeast-2.elb.amazonaws.com'
    expect hit 0 'instance i-0123456789abcdef0 is running'
    expect hit 0 'group sg-0123456789abcdef0'
    expect hit 0 "key $key leaked"
    expect hit 1 '계정 123456789012 입니다'
    expect hit 0 '서버 8.8.4.4 에 접속'
    expect hit 0 '접속 주소는 8.8.4.4.'

    # 잡으면 안 되는 것
    expect ok 0 '<계정ID>.dkr.ecr.ap-northeast-2.amazonaws.com/repo'
    expect ok 0 '엔드포인트 <RDS 엔드포인트>, 인스턴스 <인스턴스 ID>, 탄력적 IP <탄력적 IP>'
    expect ok 0 'i-placeholder sg-xxxx vpc-xxxx subnet-abc'
    expect ok 0 '사설 10.0.151.203, 172.16.5.4, 192.168.0.1, 127.0.0.1, 0.0.0.0'
    expect ok 0 '문서용 예약 대역 203.0.113.10, 198.51.100.7, 192.0.2.1'
    expect ok 0 'MySQL 8.4.11, Spring Boot 4.1.1, Java 21, 4.5.6.7.8'
    expect ok 0 '코드의 큰 숫자 상수 123456789012L'
    expect ok 1 '13자리 타임스탬프 1760000000000, 11자리 12345678901'
    expect ok 0 '공개 DNS 8.8.8.8 # identifier-check: allow'

    if [[ $fail -ne 0 ]]; then
        exit 1
    fi
    echo "자체 점검 통과"
}

case "${1:-}" in
    "" | --all) run_scan worktree ;;
    --staged) run_scan staged ;;
    --self-test) self_test ;;
    -h | --help) sed -n '2,15p' "$0" ;;
    *) echo "알 수 없는 옵션: $1 (--staged, --self-test, --help)" >&2; exit 2 ;;
esac
