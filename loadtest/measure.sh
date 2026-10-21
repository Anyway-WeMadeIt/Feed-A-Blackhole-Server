#!/bin/bash
# 요청(로그)당 앱·DB CPU 시간과 SQL 문장 수를 잰다. 코드나 설정을 바꾸기 전후를 같은 방법으로 비교하는 용도다.
#
# 사용: loadtest/measure.sh <이름> [JVM 옵션]
#   예) loadtest/measure.sh before
#       loadtest/measure.sh g1 "-XX:+UseG1GC"
#
# 매번 새 DB로 시작하고, 정상 크기 요청(로그 2개)·로그 50개 묶음·로그 500개 묶음을 고정 요청률로 60초씩 보낸다.
# 앱이 JIT로 달궈지도록 먼저 약 100초 예열한다. 커밋 동기화(fsync)는 완화한다: 이 PC의 디스크 지연이 앱 비용을 가려서
# (운영 RDS와도 다르다) 앱 자체의 비용만 보려는 것이다. 요청 하나당 커밋 수는 따로 출력한다.
# 앱 CPU는 컨테이너의 cgroup 사용 시간으로, SQL 문장 수는 MySQL의 Questions 증가분으로 잰다.
set -u
cd "$(dirname "$0")"
LABEL="${1:-run}"
export APP_JAVA_OPTS="${2:-}" APP_CPUS="${APP_CPUS:-2}" DB_CPUS="${DB_CPUS:-2}"
M="docker compose exec -T mysql mysql -uroot -ploadtest-root"

docker compose down -v > /dev/null 2>&1
docker compose up -d app > /dev/null 2>&1
for _ in $(seq 1 100); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:18080/actuator/health)
  [ "$code" = 200 ] && break
  sleep 3
done
$M -e "SET GLOBAL innodb_flush_log_at_trx_commit=2; SET GLOBAL sync_binlog=0" 2>&1 | grep -v Warning

cpu() { docker exec "$1" sh -c "grep usage_usec /sys/fs/cgroup/cpu.stat" | cut -d' ' -f2; }
status() { $M -N -e "SHOW GLOBAL STATUS LIKE '$1'" 2> /dev/null | awk '{print $2}'; }
app=$(docker compose ps -q app)
db=$(docker compose ps -q mysql)

# 예열(약 4,000건)
SCENARIO=abuse BATCH=2 ABUSE_RATE=40 DURATION=100s VUS=100 docker compose run --rm k6 > /dev/null 2>&1

run() { # 이름 요청당_로그 초당_요청 VU
  local a0 d0 q0 c0 out a1 d1 q1 c1 n p95 avg
  a0=$(cpu "$app"); d0=$(cpu "$db"); q0=$(status Questions); c0=$(status Com_commit)
  out=$(SCENARIO=abuse BATCH=$2 ABUSE_RATE=$3 DURATION=60s VUS=$4 docker compose run --rm k6 2>&1)
  a1=$(cpu "$app"); d1=$(cpu "$db"); q1=$(status Questions); c1=$(status Com_commit)
  n=$(echo "$out" | grep -E "^\s+iterations" | sed -E 's/ +/ /g' | cut -d' ' -f3)
  p95=$(echo "$out" | grep -E "^\s+\{ name:sync" | sed -E 's/.*p\(95\)=([^ ]+).*/\1/')
  avg=$(echo "$out" | grep -E "^\s+\{ name:sync" | sed -E 's/.*avg=([^ ]+) .*/\1/')
  awk -v l="$LABEL" -v nm="$1" -v b="$2" -v n="$n" -v a=$((a1 - a0)) -v d=$((d1 - d0)) -v q=$((q1 - q0)) -v c=$((c1 - c0)) \
    -v p95="$p95" -v avg="$avg" 'BEGIN {
      printf "[%s %s] %d로그/요청, %d요청: 앱 %.2f ms/요청(%.2f ms/로그), DB %.2f ms/요청, SQL %.1f문장/요청, 커밋 %.1f/요청, 지연 평균=%s p95=%s\n",
        l, nm, b, n, a / 1000 / n, a / 1000 / n / b, d / 1000 / n, q / n, c / n, avg, p95 }'
}
run small 2 40 100
run batch50 50 10 100
run batch500 500 1 50

docker compose --profile k6 down -v > /dev/null 2>&1
