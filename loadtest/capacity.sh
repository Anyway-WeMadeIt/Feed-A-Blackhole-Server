#!/bin/bash
# 서버가 "응답이 느려지거나 요청이 밀리기 전까지" 초당 몇 건(= 요청당 로그 1개 가정이면 초당 몇 로그)을 받는지 잰다.
#
# 사용: loadtest/capacity.sh <이름> [소스 폴더]
#   예) loadtest/capacity.sh after                       # 현재 작업 트리
#       loadtest/capacity.sh before /path/to/old-worktree # 개선 전 커밋을 체크아웃한 작업 트리
#
# 요청 하나에 로그 하나를 실어 요청률을 단계로 올린다(상시 온라인 클라이언트: 행위마다 곧바로 동기화).
# 통과 기준: p95 응답 500ms 이하, 밀린(dropped) 요청 0, 오류 0. 처음 기준을 넘는 단계에서 멈추고, 그 앞 단계가 용량이다.
# 두 가지 CPU 상태를 같은 서버 프로세스로 이어서 잰다(JIT가 달궈진 상태를 유지하려고 재시작하지 않는다):
#   A) 크레딧이 있는 상태: 앱 2 CPU, DB 2 CPU
#   B) 크레딧이 바닥난 기준 성능: 앱 0.4 CPU(t3.small 20%x2), DB 0.2 CPU(db.t4g.micro 10%x2). docker update로 바꾼다.
# 커밋 동기화(fsync)는 완화한다(measure.sh와 같은 이유). 이 PC는 운영 인스턴스보다 빨라 절대값이 아니라 전후 비교용이다.
set -u
cd "$(dirname "$0")"
LABEL="${1:?이름을 준다}"
SRC="${2:-}"
if [ -n "$SRC" ]; then
  command -v cygpath > /dev/null && SRC="$(cygpath -m "$SRC")"
  export APP_SRC="$SRC"
fi
export APP_CPUS=2 DB_CPUS=2 APP_JAVA_OPTS=""
RATES_A="${RATES_A:-20 40 60 80 100 130 160 200 250 320}"
RATES_B="${RATES_B:-5 10 15 20 30 40 60 80}"
STEP_SECONDS="${STEP_SECONDS:-50}"
M="docker compose exec -T mysql mysql -uroot -ploadtest-root"

docker compose down -v > /dev/null 2>&1
docker compose build app 2>&1 | tail -1
docker compose up -d app > /dev/null 2>&1
for _ in $(seq 1 100); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:18080/actuator/health)
  [ "$code" = 200 ] && break
  sleep 3
done
$M -e "SET GLOBAL innodb_flush_log_at_trx_commit=2; SET GLOBAL sync_binlog=0" 2>&1 | grep -v Warning
app=$(docker compose ps -q app)
db=$(docker compose ps -q mysql)
cpu() { docker exec "$1" sh -c "grep usage_usec /sys/fs/cgroup/cpu.stat" | cut -d' ' -f2; }

# 예열: 크레딧 상태에서 요청 약 5,000건
SCENARIO=abuse BATCH=1 ABUSE_RATE=50 DURATION=100s VUS=150 docker compose run --rm k6 > /dev/null 2>&1

# p95 문자열(예: 612.5µs, 44.2ms, 1.23s)을 ms로
to_ms() { awk -v v="$1" 'BEGIN { n = v + 0; if (v ~ /µs$/) n = n / 1000; else if (v ~ /ms$/) n = n; else if (v ~ /s$/) n = n * 1000; printf "%.0f", n }'; }

# 한 단계를 재고 통과 여부를 돌려준다(0 = 통과)
step() { # 이름 초당_요청 VU
  local a0 a1 t0 t1 out p95 p95ms drop err done cores
  a0=$(cpu "$app"); t0=$(date +%s.%N)
  out=$(SCENARIO=abuse BATCH=1 ABUSE_RATE=$2 DURATION=${STEP_SECONDS}s VUS=$3 docker compose run --rm k6 2>&1)
  a1=$(cpu "$app"); t1=$(date +%s.%N)
  p95=$(echo "$out" | grep -E "^\s+\{ name:sync" | sed -E 's/.*p\(95\)=([^ ]+).*/\1/')
  p95ms=$(to_ms "${p95:-0s}")
  drop=$(echo "$out" | grep dropped_iterations | sed -E 's/ +/ /g' | cut -d' ' -f3); drop=${drop:-0}
  err=$(echo "$out" | grep -E "sync_errors\.+:" | sed -E 's/ +/ /g' | cut -d' ' -f3)
  done=$(echo "$out" | grep -E "^\s+iterations" | sed -E 's/ +/ /g' | cut -d' ' -f4)
  cores=$(awk -v a=$((a1 - a0)) -v t0=$t0 -v t1=$t1 'BEGIN { printf "%.2f", a / 1e6 / (t1 - t0) }')
  local verdict=PASS
  if [ "$p95ms" -gt 500 ] || [ "$drop" != 0 ] || [ "${err:-}" != "0.00%" ]; then verdict=FAIL; fi
  echo "[$LABEL/$1] 요청 $2건/초 -> 처리 ${done} p95=${p95ms}ms 밀림=$drop 오류=${err:-?} 앱 코어=$cores : $verdict"
  [ "$verdict" = PASS ]
}

# SKIP_A=1이면 크레딧 있음 구간을 건너뛰고 소진 구간만 잰다. A 구간이 과부하로 끝나면 그 상태(큐, GC, DB 부하)가 B 구간 결과를 오염시킨다.
if [ "${SKIP_A:-0}" != 1 ]; then
  echo "== $LABEL: A) 크레딧 있음(앱 2, DB 2 CPU)"
  for r in $RATES_A; do step credit "$r" 300 || break; done
fi
echo "== $LABEL: B) 크레딧 소진(앱 0.4, DB 0.2 CPU)"
docker update --cpus=0.4 "$app" > /dev/null && docker update --cpus=0.2 "$db" > /dev/null
sleep 5
for r in $RATES_B; do step baseline "$r" 150 || break; done

docker compose --profile k6 down -v > /dev/null 2>&1
