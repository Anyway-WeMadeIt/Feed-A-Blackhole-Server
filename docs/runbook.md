# 운영 런북

운영 서버를 돌보는 사람이 **알림을 받았을 때, 배포·롤백·점검을 할 때, 비밀값을 바꿀 때** 순서대로 따라 하는 문서다. 배경과 설계는 [deploy-plan.md](deploy-plan.md), HTTPS 프록시는 [../deploy/nginx/README.md](../deploy/nginx/README.md), 배포 스크립트는 [../deploy/deploy.sh](../deploy/deploy.sh)를 본다.

이 저장소는 공개라서 계정 ID, 엔드포인트, 인스턴스 ID, 공인 IP는 적지 않는다([CONTRIBUTING.md](../CONTRIBUTING.md)의 "인프라 식별 정보"). 아래의 `<…>`는 AWS 콘솔이나 비밀번호 관리자에서 확인해 바꿔 쓴다.

**검증 표시**: `검증됨`은 실제 서버에서 해 본 절차이고, `미검증`은 문서로만 있는 절차다. 미검증 절차는 처음 쓸 때 결과로 이 문서를 고친다.

## 1. 한눈에 보기

```
클라이언트 ─HTTPS─▶ Nginx(EC2) ─▶ 앱 컨테이너(127.0.0.1:8080) ─▶ RDS(MySQL 8.4, 프라이빗 서브넷)
```

| 구성 요소 | 이름·위치 | 비고 |
| --- | --- | --- |
| 리전 | `ap-northeast-2`(서울) | 콘솔 우측 상단이 서울인지 먼저 본다 |
| 서버 | EC2 `blackhole-app`(`t3.small`, x86_64, Amazon Linux 2023) | 종료 방지 켜짐. SSH 없음 |
| 앱 | Docker 컨테이너 `blackhole` | 재시작 정책 `unless-stopped`. 포트는 `127.0.0.1:8080`만 연다 |
| DB | RDS `blackhole-db`(MySQL 8.4, `db.t4g.micro`, 단일 AZ) | 삭제 방지 켜짐. 퍼블릭 접근 없음. 자동 백업 1일 |
| 이미지 | ECR `feedablackhole-server` | 태그 = 커밋 SHA 앞 12자(변경 불가). 최근 10개 유지 |
| 설정·비밀값 | Parameter Store `/blackhole/` 아래 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` | 모두 SecureString |
| 서버 상태 파일 | `/var/lib/blackhole/last-good-tag` | 마지막으로 헬스체크를 통과한 태그(롤백 대상) |
| 알림 | SNS `blackhole-alerts`(이메일), CloudWatch 알람 7개, 외부 가동 감시(UptimeRobot, 5분 간격) | 아래 2장 |

### 서버에 접속하는 법

SSH를 쓰지 않는다. 콘솔에서 **Systems Manager → 세션 관리자 → 세션 시작 → `blackhole-app` 선택**(새 창이 열린다). 접속하면 `ssm-user`이므로 아래를 먼저 입력한다.

```bash
sudo -iu ec2-user
```

- 세션 내용은 어디에도 기록되지 않는다(로깅 꺼짐). 그래도 **비밀번호를 세션에서 타이핑해야 하면 `read -rs`로 받아 화면과 기록에 남기지 않는다**(6장 참고).
- 끝낼 때는 `exit`로 세션을 닫는다. 창만 닫으면 기록에 "종료 중"으로 잠시 남을 수 있으나 해롭지 않다.

### 서버 상태를 한 번에 보는 명령 (`검증됨`)

```bash
docker ps --format '{{.Names}}  {{.Image}}  {{.Status}}'
sudo cat /var/lib/blackhole/last-good-tag
curl -s http://127.0.0.1:8080/actuator/health
docker logs --tail 30 blackhole
```

- 정상: 컨테이너 `blackhole`이 `Up`이고, 이미지 태그와 `last-good-tag`가 같고, 헬스체크가 `"status":"UP"`이다.

## 2. 알림이 왔을 때

알림은 이메일로 오고, 보내는 곳은 세 가지다. 어떤 알림이든 먼저 **"지금 서비스가 되는가"**부터 확인한다.

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://<도메인>/actuator/health
```

`200`이면 서비스는 되고 있는 것이다. 아니면 아래 "장애 대응 순서"로 간다.

| 알림 | 의미 | 첫 대응 |
| --- | --- | --- |
| UptimeRobot "Down" | 밖에서 `https://<도메인>/actuator/health`가 2xx가 아니다 | 위 명령으로 확인 → "장애 대응 순서" |
| `blackhole-ec2-status-check-failed` | EC2 상태 체크가 3분 연속 실패. 인스턴스를 중지해도 이 알람이 울린다 | 콘솔 EC2 → 상태 검사 탭 확인. 시스템 문제면 인스턴스 재부팅 |
| `blackhole-ec2-cpu-high` | CPU 80% 초과가 15분 지속 | `docker stats --no-stream`, Nginx 접근 로그에서 요청이 몰렸는지 본다 |
| `blackhole-ec2-cpu-credit-low` | CPU 크레딧 잔량 50 미만. 바닥나면 `t3.small`이 기준 성능(약 20%)으로 조여져 느려진다(죽지는 않음) | CPU가 계속 높은 원인을 찾는다. 해소가 안 되면 크레딧 모드 변경이나 한 단계 큰 인스턴스를 검토 |
| `blackhole-rds-free-storage-low` | DB 여유 공간 2GiB 미만 | 데이터가 늘었는지 확인하고 스토리지를 늘린다(콘솔 → 수정) |
| `blackhole-rds-freeable-memory-low` | DB 여유 메모리 100MiB 미만이 15분 지속(1GiB 인스턴스) | 연결 수와 무거운 쿼리를 본다. 지속되면 한 단계 큰 인스턴스를 검토 |
| `blackhole-rds-cpu-credit-low` | DB CPU 크레딧 잔량 30 미만 | 위 `cpu-high`와 같이 부하 원인을 본다 |
| `blackhole-rds-connections-high` | DB 연결이 50을 넘는 상태가 10분 지속 | 앱 컨테이너 재시작으로 연결을 정리해 보고, 반복되면 풀 크기와 요청 패턴을 점검 |

- 알람이 `OK`로 돌아오면 복귀 메일이 한 번 더 온다.
- 알림 경로 점검: `aws cloudwatch set-alarm-state --alarm-name <알람 이름> --state-value ALARM --state-reason "TEST"`로 시험 알림을 보낼 수 있다(`검증됨`, 2026-10-19).

### 장애 대응 순서

위에서부터 한 단계씩 확인하고, 원인을 찾으면 멈춘다.

1. **방금 배포했나?** GitHub Actions의 `Deploy` 실행을 본다. 배포 직후라면 4장의 롤백을 먼저 고려한다.
2. **컨테이너가 떠 있나?** 1장의 "서버 상태를 한 번에 보는 명령". `Exited`나 반복 재시작이면 `docker logs --tail 100 blackhole`에서 원인을 본다.
3. **헬스체크가 503인가?** 앱은 DB에 연결할 수 없을 때 `503`을 준다(응답에 최대 약 30초 걸린다). RDS 콘솔에서 `blackhole-db` 상태를 보고, 보안그룹 규칙(DB는 앱 서버에서 오는 3306만 허용)이 바뀌지 않았는지 확인한다.
4. **Nginx나 인증서 문제인가?** 앱은 정상인데 밖에서만 안 되면 `sudo systemctl status nginx`, `sudo certbot certificates`(만료일), `sudo nginx -t`. 로그는 `/var/log/nginx/error.log`.
5. **서버 자체가 문제인가?** EC2 상태 검사. 서버가 응답하지 않으면 콘솔에서 재부팅한다. 재부팅하면 Docker와 컨테이너가 스스로 올라온다(`검증됨`).
6. **그래도 모르면** 마지막으로 정상이던 이미지로 되돌린다(4장). 롤백은 DB 마이그레이션 때문에 막힐 수 있으니 4장의 주의를 본다.

## 3. 정기 점검 (주 1회, 5분)

| 점검 | 방법 | 기준 |
| --- | --- | --- |
| 크레딧과 비용 | 콘솔 Billing → 크레딧, Cost Explorer. 크레딧 잔액은 8장의 명령으로도 본다 | 잔액과 이번 달 사용량이 예상 범위인지. 소진 예상 시점은 8장 |
| 인증서 만료일 | EC2에서 `sudo certbot certificates` | **30일 이상** 남아 있어야 한다. 갱신이 실패해도 메일이 오지 않는다 |
| 디스크 | `df -h /`, `docker system df` | 루트 사용률이 70%를 넘지 않게. 이미지는 `deploy.sh`가 7일 지난 미사용 이미지를 지운다 |
| 알람 상태 | CloudWatch → 알람 | 7개 모두 `OK` |
| 가동률 | UptimeRobot 대시보드 | 끊김 기록이 없는지 |
| 최근 배포 | GitHub Actions → Deploy | 마지막 실행이 성공인지 |

## 4. 배포와 롤백

### 자동 배포 (`검증됨`, 2026-10-19)

`dev` → `main` PR을 **병합 커밋**으로 병합하면 시작된다. 병합 직후에는 `dev`가 `main`보다 한 커밋 뒤처지므로, 다음 PR 전에 `dev`에 `main`을 합쳐 둔다(`Update branch`, 방식은 merge commit).

1. Actions → `Deploy` 실행에서 `Test before deploy`가 통과하는지 본다.
2. `Build, push and deploy`가 `production` 환경 승인을 기다린다. **Review deployments → production → Approve and deploy**. 승인 전에 대상 커밋을 확인한다.
3. 이미지 빌드·푸시 → SSM으로 `deploy.sh` 실행 → 헬스체크. 승인 뒤 약 2분, 테스트까지 합치면 전체 약 5분이 걸린다(2026-10-19 실측).
4. 끝나면 1장의 "서버 상태를 한 번에 보는 명령"으로 태그가 새 커밋 SHA(앞 12자)인지 확인한다.

같은 커밋을 다시 배포하려면 Actions → Deploy → **Run workflow**(브랜치 `main`). 이미 ECR에 있는 이미지는 다시 빌드하지 않는다.

### 결과 해석

| Actions 결과 | 서버 상태 | 다음 행동 |
| --- | --- | --- |
| 초록색 | 새 버전이 헬스체크를 통과해 운영 중 | 위 4번 확인 |
| 빨강, 로그에 "되돌리기 성공" | **이전 버전이 복구되어 서비스 중**(새 버전은 실패) | 로그의 실패 원인을 고쳐 다시 배포 |
| 빨강, "되돌리기도 실패했습니다" 또는 "되돌릴 이전 정상 태그가 없습니다" | 서비스가 내려가 있다 | 아래 "수동 롤백"을 즉시 한다 |
| 빨강, 서버 단계 이전(로그인, 빌드, 푸시) | 서버는 변하지 않았다 | 원인을 고쳐 `Re-run failed jobs` |

롤백에 성공해도 이번 배포는 **실패(빨강)로 표시된다**. 서버가 살아 있는지는 로그와 헬스체크로 따로 본다.

### 수동 배포·롤백 (`검증됨`)

`deploy.sh`는 서버에 상시 두지 않는다. 저장소를 받아 실행하고 지운다. 돌아갈 태그는 ECR 콘솔의 이미지 목록(태그 = 커밋 SHA 앞 12자)이나 GitHub 커밋 목록에서 찾는다.

```bash
sudo -iu ec2-user
git clone --depth 1 https://github.com/Anyway-WeMadeIt/Feed-A-Blackhole-Server.git ~/repo
sudo bash ~/repo/deploy/deploy.sh <돌아갈 태그>
rm -rf ~/repo
```

- 설정을 먼저 검증하고(기존 컨테이너는 그대로), 새 이미지를 받은 뒤, 기존 컨테이너를 정상 종료하고 교체한다. 헬스체크가 실패하면 직전 정상 태그로 자동으로 되돌린다.
- 교체 중에는 **약 20초 서비스가 끊긴다**(서버 1대 구성).
- 종료 코드 0이 성공이다.

### 롤백의 한계: DB 마이그레이션

롤백은 **이미지만** 되돌린다. DB 스키마는 되돌리지 않는다. 이미 적용된 마이그레이션이 열을 지우거나 이름을 바꿨다면, 그 마이그레이션 **이전** 코드의 이미지는 시작할 때 JPA 검증(`ddl-auto=validate`)에서 실패한다. 그래서:

- 되돌릴 대상은 **현재 DB 스키마와 호환되는 이미지**여야 한다(적용된 마이그레이션 번호 이상의 코드).
- 스키마를 바꾸는 배포 전에는 RDS **수동 스냅샷**을 만든다(6장). 열 삭제처럼 되돌리기 어려운 변경은 두 번에 나눠 배포한다.

적용된 마이그레이션 확인:

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
```

## 5. 로그

| 무엇 | 어디서 |
| --- | --- |
| 앱 | `docker logs --tail 100 blackhole`(실시간은 `-f`, 시간 범위는 `--since 30m`) |
| Nginx | `/var/log/nginx/access.log`, `error.log` |
| 인증서 갱신 | `sudo journalctl -u certbot-renew` |
| 배포 | GitHub Actions 실행 로그(**GitHub 로그인이 필요**), 콘솔 Systems Manager → Run Command → 명령 기록(출력이 길면 잘린다) |

- 컨테이너 로그는 **크기 제한이 있다**(파일당 10MB, 3개 회전). 오래된 로그는 회전으로 사라진다.
- **컨테이너를 교체하면 이전 컨테이너의 로그는 함께 사라진다.** 배포 직후 문제를 분석해야 하는데 로그가 필요하면, 롤백이 일어났을 때 새 버전의 마지막 40줄은 `deploy.sh` 출력(Actions 로그)에 남는다.
- 로그를 CloudWatch Logs로 보내는 것은 아직 하지 않았다(9장).

## 6. DB

### 접속 (`검증됨`: 쿼리 한 번을 실행하는 방식. 대화형 `-it`는 같은 이미지·같은 인자로 쓰지만 실제로 해 보지는 않았다)

서버에 `mysql` 클라이언트는 없다. 일회용 컨테이너로 쓴다. `<RDS 엔드포인트>`는 RDS 콘솔의 `blackhole-db`에서 확인한다.

**앱 계정**(`blackhole_app`, `feedablackhole` 스키마 권한만 있음)으로 조회할 때:

```bash
sudo -iu ec2-user
APP_PW=$(aws ssm get-parameter --name /blackhole/DB_PASSWORD --with-decryption --region ap-northeast-2 --query Parameter.Value --output text)
docker run --rm -it -e MYSQL_PWD="$APP_PW" mysql:8.4 mysql -h <RDS 엔드포인트> -u blackhole_app feedablackhole
unset APP_PW
```

**마스터 계정**(`blackhole`, 계정·권한 관리용)은 비밀번호를 화면에 남기지 않고 받는다. 이 두 줄은 먼저 입력하고 프롬프트에 비밀번호를 친 뒤, 아래 명령을 실행한다.

```bash
read -rsp "RDS master password: " MYSQL_PWD; echo; export MYSQL_PWD
docker run --rm -it -e MYSQL_PWD mysql:8.4 mysql -h <RDS 엔드포인트> -u blackhole
unset MYSQL_PWD
```

- DB는 프라이빗 서브넷에 있어 **내 PC에서는 접속되지 않는다.** 항상 서버를 거친다.
- 운영 데이터를 직접 바꾸는 작업(`UPDATE`, `DELETE`)은 하기 전에 스냅샷을 만든다.

### 수동 스냅샷 (`검증됨`: 2026-10-19, 몇 분 이내)

자동 백업은 1일만 보관한다(무료 플랜 제한). 중요한 변경 전에는 수동 스냅샷을 만든다. 수동 스냅샷은 지울 때까지 남는다.

```bash
aws rds create-db-snapshot --region ap-northeast-2 --db-instance-identifier blackhole-db --db-snapshot-identifier <이름-날짜>
aws rds describe-db-snapshots --region ap-northeast-2 --db-snapshot-identifier <이름-날짜> --query "DBSnapshots[0].Status"
```

상태가 `available`이면 끝난 것이다.

### 복구 (`검증됨`: 2026-10-19 복구 시험, 인스턴스 생성부터 사용 가능까지 약 8분)

스냅샷에서의 복구는 **기존 DB를 되돌리는 것이 아니라 새 인스턴스를 만드는 것**이다. 새 인스턴스는 엔드포인트가 다르다.

시험에서는 아래 명령으로 복원했고, 서버에서 **같은 앱 계정과 같은 `DB_PASSWORD`로 접속**됐다. Flyway 버전(4)과 테이블 구성도 운영과 같았다. 계정과 비밀번호가 스냅샷과 함께 복구되므로 비밀번호를 새로 만들 필요가 없다.

1. 스냅샷으로 새 인스턴스를 만든다. 서브넷 그룹과 보안그룹, 비공개 설정을 **반드시 명시한다**(콘솔에서 할 때도 같은 값).

   ```bash
   aws rds restore-db-instance-from-db-snapshot --region ap-northeast-2 \
     --db-instance-identifier <새 이름> --db-snapshot-identifier <스냅샷 이름> \
     --db-instance-class db.t4g.micro --db-subnet-group-name blackhole-db-subnet-group \
     --vpc-security-group-ids <blackhole-sg-db의 ID> --no-publicly-accessible --no-multi-az --no-deletion-protection
   aws rds wait db-instance-available --region ap-northeast-2 --db-instance-identifier <새 이름>
   ```
2. 서버에서 앱 계정으로 접속해 데이터를 확인한다(위 "접속"에서 `-h`만 새 엔드포인트로).
3. 운영을 새 인스턴스로 옮길 때는 Parameter Store의 `/blackhole/DB_URL`을 새 엔드포인트로 바꾸고 같은 태그로 재배포한다(4장의 수동 절차).
4. 쓰지 않는 인스턴스는 삭제한다(운영 인스턴스는 삭제 방지가 켜져 있으므로 먼저 끈다. 시험용 복원본은 꺼서 만들었다).

   ```bash
   aws rds delete-db-instance --region ap-northeast-2 --db-instance-identifier <새 이름> --skip-final-snapshot --delete-automated-backups
   aws rds delete-db-snapshot --region ap-northeast-2 --db-snapshot-identifier <스냅샷 이름>
   ```

   삭제 뒤 `describe-db-snapshots`에 `rds:<새 이름>-…` 자동 스냅샷이 남아 보일 수 있다. 자동 스냅샷은 직접 지울 수 없고 보관 기간이 지나면 사라진다.

### 데이터 초기화와 DB 관리

**무엇이 DB에 있고 무엇이 없는가.** DB에는 **플레이어 데이터만** 있다(계정, 로그인 정보, 진행도, 산 노드와 Rank, 판 기록, 동기화 로그). 노드·전투 같은 **콘텐츠 밸런스 값은 DB가 아니라 이미지 안**(`src/main/resources/content/`)에 있고, 반영은 배포로 한다. 그래서 초기화는 콘텐츠를 되돌리지 않는다. 밸런스를 바꾸면 이미 산 노드의 Rank가 새 기준과 안 맞을 수 있어서, 테스트 단계에서는 데이터를 비우고 다시 시작하는 것이 가장 단순하다.

SQL은 로컬 MySQL 8.4에 마이그레이션(V1~V4)을 적용하고 시험 데이터를 넣어 먼저 확인했다(외래 키 순서, 한 계정 삭제, 전체 삭제, ID 초기화, 스키마 재생성 뒤 앱 계정 권한 유지). 그중 **"테스트 계정 전체 초기화"는 2026-10-19에 운영 RDS에서 실행해 오류 없이 끝났다(`검증됨`).** "계정 하나만 지우기"와 "스키마까지 초기화"는 로컬에서만 확인했고 RDS에서는 아직 실행하지 않았다(`미검증`). 처음 쓸 때 결과로 이 절을 고친다.

공통 준비: 위 "접속"처럼 서버에서 **마스터 계정**으로 접속한다(아래 명령의 `<RDS 엔드포인트>`). 마스터는 비밀번호를 화면에 남기지 않고 받는다.

```bash
sudo -iu ec2-user
read -rsp "RDS master password: " MYSQL_PWD; echo; export MYSQL_PWD
```

끝나면 반드시 `unset MYSQL_PWD`를 한다.

#### 지금 어떤 데이터가 있는지 보기

```bash
docker run --rm -i -e MYSQL_PWD mysql:8.4 mysql -h <RDS 엔드포인트> -u blackhole feedablackhole <<'SQL'
SELECT (SELECT COUNT(*) FROM account) accounts, (SELECT COUNT(*) FROM auth_identity) identities,
       (SELECT COUNT(*) FROM refresh_token) tokens, (SELECT COUNT(*) FROM player_progress) progress,
       (SELECT COUNT(*) FROM player_node_rank) node_ranks, (SELECT COUNT(*) FROM battle_run) runs,
       (SELECT COUNT(*) FROM sync_log) sync_logs;
SELECT id, created_at, last_login_at FROM account ORDER BY id DESC LIMIT 10;
SQL
```

#### 테스트 계정 전체 초기화 (스키마 유지, `검증됨`: 2026-10-19)

밸런스를 바꿨거나 시험 데이터를 정리할 때 쓴다. **모든 계정과 진행 데이터가 사라진다.** 테이블 구조와 Flyway 이력(`flyway_schema_history`)은 그대로라서 재배포나 재시작이 필요 없다.

1. 되돌릴 수 있게 **수동 스냅샷**을 만든다(위 "수동 스냅샷"). 끝나기를 기다린다.
2. (권장) 쓰는 중에 새 데이터가 들어오지 않도록 앱을 잠시 멈춘다: `docker stop blackhole`. 몇 초면 끝나지만 그동안 서비스가 끊긴다.
3. 삭제한다. 한 트랜잭션이라 중간에 실패하면 아무것도 지워지지 않는다. 외래 키 때문에 **자식 테이블부터** 지운다.

   ```bash
   docker run --rm -i -e MYSQL_PWD mysql:8.4 mysql -h <RDS 엔드포인트> -u blackhole feedablackhole <<'SQL'
   START TRANSACTION;
   DELETE FROM sync_log;
   DELETE FROM player_node_rank;
   DELETE FROM player_progress;
   DELETE FROM battle_run;
   DELETE FROM refresh_token;
   DELETE FROM auth_identity;
   DELETE FROM account;
   COMMIT;
   ALTER TABLE sync_log AUTO_INCREMENT = 1;
   ALTER TABLE player_node_rank AUTO_INCREMENT = 1;
   ALTER TABLE battle_run AUTO_INCREMENT = 1;
   ALTER TABLE refresh_token AUTO_INCREMENT = 1;
   ALTER TABLE auth_identity AUTO_INCREMENT = 1;
   ALTER TABLE account AUTO_INCREMENT = 1;
   SELECT (SELECT COUNT(*) FROM account) accounts, (SELECT COUNT(*) FROM flyway_schema_history) migrations;
   SQL
   ```

   `accounts`가 0이고 `migrations`가 4(적용된 마이그레이션 수)면 된다. 마지막 `ALTER`는 ID를 1부터 다시 시작하게 할 뿐이라 빼도 된다.
4. 앱을 다시 켠다: `docker start blackhole` 후 1장의 상태 확인 명령으로 `UP`을 본다.
5. `unset MYSQL_PWD`.

**초기화 뒤 영향**: 단말기에 저장된 게스트 정보와 리프레시 토큰은 서버에 없는 값이 된다. 클라이언트가 "서버가 모르는 게스트"를 만나면 **다시 등록(`guest/register`)하도록 처리하는지 확인해야 한다**(클라이언트 쪽은 확인하지 못했다). 처리하지 않으면 테스트 기기에서 앱 데이터를 지워야 한다. 액세스 토큰은 최대 15분 동안 남아 있지만 계정이 없어 요청이 실패한다.

#### 계정 하나만 지우기 (`미검증`: 로컬에서만 확인)

특정 테스트 계정만 정리할 때(`<계정 ID>`는 위 조회에서 확인).

```bash
docker run --rm -i -e MYSQL_PWD mysql:8.4 mysql -h <RDS 엔드포인트> -u blackhole feedablackhole <<'SQL'
SET @id = <계정 ID>;
START TRANSACTION;
DELETE FROM sync_log         WHERE account_id = @id;
DELETE FROM player_node_rank WHERE account_id = @id;
DELETE FROM player_progress  WHERE account_id = @id;
DELETE FROM battle_run       WHERE account_id = @id;
DELETE FROM refresh_token    WHERE account_id = @id;
DELETE FROM auth_identity    WHERE account_id = @id;
DELETE FROM account          WHERE id = @id;
COMMIT;
SQL
```

앱을 멈출 필요는 없다. 실제 사용자가 생긴 뒤에는 **전체 초기화를 하지 않고** 이 방식으로 계정 단위로만 지우며, 지우기 전에 스냅샷을 만든다.

#### 스키마까지 초기화 (테이블을 모두 다시 만들 때, `미검증`: 로컬에서만 확인)

마이그레이션 파일을 **이미 운영에 적용된 뒤에 고쳐야 할 때**(출시 전에만) 쓴다. 적용된 마이그레이션은 체크섬이 기록돼 있어서 파일을 고치면 Flyway가 앱 시작을 거부한다. 데이터뿐 아니라 **모든 테이블과 Flyway 이력이 사라지고**, 다음 시작 때 Flyway가 V1부터 다시 만든다.

1. 수동 스냅샷을 만든다. 현재 DB의 문자셋을 기록해 둔다(재생성 때 같은 값으로 만든다).

   ```bash
   docker run --rm -i -e MYSQL_PWD mysql:8.4 mysql -h <RDS 엔드포인트> -u blackhole -e "SELECT default_character_set_name, default_collation_name FROM information_schema.schemata WHERE schema_name='feedablackhole'"
   ```
2. 앱을 멈춘다: `docker stop blackhole`.
3. 스키마를 지우고 다시 만든다. 앱 계정의 스키마 권한은 `DROP DATABASE` 뒤에도 남는다(로컬에서 확인).

   ```bash
   docker run --rm -i -e MYSQL_PWD mysql:8.4 mysql -h <RDS 엔드포인트> -u blackhole <<'SQL'
   DROP DATABASE feedablackhole;
   CREATE DATABASE feedablackhole;
   SQL
   ```

   1에서 본 값이 기본값(`utf8mb4`, `utf8mb4_0900_ai_ci`)과 다르면 `CREATE DATABASE`에 `CHARACTER SET`과 `COLLATE`를 같은 값으로 붙인다.
4. **같은 태그로 앱을 다시 시작**해 Flyway가 테이블을 만들게 한다. 4장의 "수동 배포·롤백"의 `deploy.sh <현재 태그>`를 쓴다(현재 태그는 `sudo cat /var/lib/blackhole/last-good-tag`). 헬스체크가 `UP`이면 끝이다.
5. 확인: `SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;`가 V1부터 모두 `success=1`인지 본다. `unset MYSQL_PWD`.

#### DB를 다룰 때의 규칙

- **스키마는 Flyway 마이그레이션으로만 바꾼다.** 서버에서 손으로 `ALTER TABLE`을 하지 않는다. 앱이 시작할 때 JPA가 엔티티와 스키마를 대조(`validate`)해서, 어긋나면 서버가 뜨지 않는다.
- **운영에 적용된 마이그레이션 파일은 고치지 않는다.** 바꿀 내용은 새 번호의 마이그레이션(`V5__...`)으로 추가한다. 출시 전에 정말 고쳐야 하면 위 "스키마까지 초기화"를 쓴다.
- Flyway의 `clean`(스키마 전체 삭제 명령)은 꺼진 기본값을 유지한다. 앱 설정에서 켜지 않는다.
- 운영 데이터를 직접 바꾸는 모든 작업 전에는 스냅샷을 만든다. 작업 후에는 `unset MYSQL_PWD`와 세션 종료까지 한다.
- 앱 계정(`blackhole_app`)으로는 조회와 단발 수정을, 마스터 계정(`blackhole`)으로는 초기화처럼 구조·권한에 닿는 작업만 한다.
- 출시 뒤 `sync_log`(받은 로그 원문)가 계속 쌓여 용량이 늘면 오래된 행을 지울 수 있다. 어디서도 이 테이블을 참조하지 않아 안전하다(아직 실행해 보지 않았다): `DELETE FROM sync_log WHERE received_at < UTC_TIMESTAMP(6) - INTERVAL 90 DAY;` 저장공간 알람(`blackhole-rds-free-storage-low`)이 울리면 먼저 용량을 본다.

## 7. 비밀값 교체

모든 비밀값은 Parameter Store `/blackhole/`에 있다. **값을 채팅·이슈·PR·로그에 붙이지 않는다.** 값을 바꾼 뒤에는 컨테이너가 새 값을 읽도록 **같은 태그로 `deploy.sh`를 다시 실행**한다(4장의 수동 절차). 컨테이너는 시작할 때만 환경변수를 읽는다.

| 값 | 교체 절차 | 영향 |
| --- | --- | --- |
| `JWT_SECRET` | ① 새 키 생성(`python -c "import secrets;print(secrets.token_urlsafe(48))"`, 32자 이상) ② Parameter Store에서 값 덮어쓰기 ③ 같은 태그로 재배포 | 이미 발급된 **액세스 토큰(수명 15분)이 모두 무효**가 된다. 리프레시 토큰은 DB에 있어 영향이 없고, 클라이언트는 `401`을 받으면 갱신해서 새 키로 다시 발급받는다 |
| `DB_PASSWORD`(앱 계정) | ① 새 비밀번호 생성 ② 마스터 계정으로 `ALTER USER 'blackhole_app'@'10.0.%' IDENTIFIED BY '<새 비밀번호>'` ③ **바로** Parameter Store 덮어쓰기 ④ 같은 태그로 재배포 | ②~④ 사이에는 새 연결이 실패한다. 기존 연결은 유지되지만 연결 풀이 갱신하는 순간부터 오류가 난다. 끊김을 줄이려면 ②~④를 연달아 한다 |
| RDS 마스터 비밀번호 | RDS 콘솔 → 수정 → 마스터 비밀번호 | 앱은 마스터를 쓰지 않아 서비스 영향 없음. 새 값은 비밀번호 관리자에 저장 |

- `DB_PASSWORD`와 `JWT_SECRET` 교체는 `미검증`이다. 처음 할 때 결과로 이 표를 고친다.
- 비밀번호가 SQL의 `'`나 `\`를 포함하면 문장이 깨진다. 영문과 숫자 위주로 만든다.

## 8. 비용과 일시 중지

- 상시 가동 시 월 **$45 안팎**으로 추정한다. 서울 리전 정가 기준으로 EC2 `t3.small` 약 $19, RDS `db.t4g.micro` 약 $18(시간당 $0.025)과 스토리지 20GiB 약 $2.6, 탄력적 IP 약 $3.6이다. 실제 값은 Cost Explorer로 확인한다.
- 이 계정은 **무료 플랜(크레딧 기반)** 이다. 크레딧이 소진되거나 무료 기간이 끝나면 서비스가 중단될 수 있으므로 크레딧 잔액을 정기 점검(3장)에서 본다.
- AWS Budgets의 월 예산 알림은 실제 사용 규모에 맞게 조정해 둔다. 항상 울리는 알림은 진짜 경고를 가린다.
- 월 예산은 **$50**이다(2026-10-19에 $30에서 올림). 알림은 실제 85%, 실제 100%, 예측 100%이고, 크레딧과 환불은 계산에서 뺀다. 즉 크레딧이 대신 내 주는 동안에도 사용액 기준으로 울린다. 정상 상태($45 안팎)에서는 울리지 않고, 구성이 늘었거나 요금이 튀면 울린다.

### 크레딧 잔액 점검 (월 1회, `검증됨`)

예산 알림은 "얼마를 썼는지"만 알려 주고 **크레딧이 언제 끝나는지는 알려 주지 않는다.** 한 달에 한 번 잔액을 확인한다.

```bash
aws freetier get-account-plan-state --region us-east-1
```

`accountPlanRemainingCredits`가 남은 크레딧(USD)이고, `accountPlanExpirationDate`가 무료 플랜 만료일이다. **소진 예상일 = 오늘 + 잔액 ÷ 하루 사용액(약 $1.49)** 이다. 하루 사용액은 Cost Explorer에서 최근 며칠의 사용(Usage) 금액으로 확인한다.

| 기록일 | 잔액 | 하루 사용액 | 소진 예상 | 만료일 |
| --- | --- | --- | --- | --- |
| 2026-10-19 | $133.99 | 약 $1.49 | **2027년 1월 중순** | 2027-03-23 |

- 소진 예상일이 만료일보다 **빠르다**. 크레딧이 끝나면 월 $45가 실제로 청구되거나 유료 플랜 전환이 필요하다. 전환하지 않으면 리소스가 막힐 수 있다(처음 겪을 때 결과로 이 문서를 고친다).
- 늦어도 **소진 예상일 한 달 전**(2026년 12월 중순)까지 계속 운영할지, 줄일지(일시 중지), 유료로 전환할지 정한다.
- 점검할 때마다 위 표에 한 줄을 더해 추세를 남긴다.

### 일시 중지와 재개 (`미검증`)

한동안 쓰지 않을 때 비용을 줄이는 방법이다.

1. UptimeRobot 모니터를 일시 중지한다(안 하면 "Down" 알림이 계속 온다).
2. EC2를 **중지**한다(삭제가 아니다. 종료 방지가 켜져 있다). 중지 중에는 `blackhole-ec2-status-check-failed` 알람이 울릴 수 있다.
3. RDS를 **중지**한다. **최대 7일 후 자동으로 다시 시작**된다.
4. 탄력적 IP와 스토리지 비용은 중지 중에도 남는다.

재개는 **RDS를 먼저** 시작하고 `사용 가능`이 된 뒤 EC2를 시작한다. EC2가 켜지면 Docker, Nginx, 앱 컨테이너가 스스로 올라온다(재부팅 복구와 같은 경로). 올라온 뒤 1장의 상태 확인 명령으로 점검한다.

## 9. 알려진 한계와 후속 과제

- **서버 1대**라 배포·재부팅 때 약 20초 끊긴다. DB도 단일 AZ다.
- **DB 스토리지가 gp2 20GiB라 보장 IOPS가 100뿐이다.** 그 이상은 버스트 크레딧(`BurstBalance`)을 쓰고, 바닥나면 DB가 크게 느려진다. 요청 하나가 쓰기 IOPS를 약 7~12 쓰므로 오래 유지할 수 있는 처리량은 초당 약 8~10건(계속 활동하는 사용자 약 85~100명)이다. **부하 테스트는 크레딧을 쓰니 같은 날 반복하지 않고, 시험 전후의 `BurstBalance`를 기록한다.** 알람은 아직 없다(gp3 변경과 알람 추가는 계획서 7단계의 후속 과제).
- **자동 백업은 1일**뿐이다. 중요한 변경 전에는 수동 스냅샷을 만든다.
- **이전 컨테이너 로그는 교체와 함께 사라진다.** 실제 사용자가 생기면 CloudWatch Logs로 컨테이너 로그를 보내는 것을 검토한다.
- 인증서 **갱신 실패 알림이 없다.** 3장의 만료일 점검으로 대신한다.
- 게스트 등록(`POST /api/v1/auth/guest/register`)은 토큰 없이 DB 행을 만든다. 요청 빈도 제한([`deploy/nginx/02-rate-limit.conf`](../deploy/nginx/02-rate-limit.conf), 2026-10-20 적용·`검증됨`)으로 IP당 분당 10회(순간 20회)까지만 받는다. 한도를 넘으면 `429`가 나가고 기록은 `sudo grep limiting /var/log/nginx/error.log`로 본다. **여러 IP를 쓰는 공격은 못 막는다.** 설정은 자동 배포로 바뀌지 않으므로, 한도를 고치면 서버의 `/etc/nginx/conf.d/02-rate-limit.conf`에 직접 반영하고 `sudo nginx -t && sudo systemctl reload nginx`를 한다.
- 도메인은 임시 무료 도메인(DuckDNS)이다. 당분간 유지하고, 바꿀 때는 아래 "정식 도메인으로 바꿀 때"를 따른다.
- 첫 자동 배포에서 롤백 경로는 CI로 실행해 보지 못했다(수동 연습과 시나리오 테스트로만 검증).

### 정식 도메인으로 바꿀 때 (`미검증`)

도메인은 **앱 코드·CD·Parameter Store에 들어 있지 않다**(JWT 발급자도 고정 문자열이라 이미 발급된 토큰이 유지된다). 바꿀 곳은 DNS, Nginx, 인증서, 외부 감시, 클라이언트 설정뿐이라 서버를 다시 배포할 필요가 없다. 핵심은 **새 도메인을 먼저 열고, 클라이언트가 옮겨 간 뒤에 옛 도메인을 닫는 것**이다.

1. **DNS**: 새 도메인의 A 레코드를 지금과 같은 탄력적 IP로 연결하고 `nslookup <새 도메인>`이 그 IP를 돌려주는지 본다. 이것이 되지 않으면 인증서 발급이 실패한다(HTTP-01이 80 포트로 소유를 확인한다).
2. **Nginx와 인증서**: `server_name`에 새 도메인을 **추가**하고 `sudo certbot --nginx --expand -d <기존 도메인> -d <새 도메인>`으로 인증서를 확장한다. 순서와 명령은 [../deploy/nginx/README.md](../deploy/nginx/README.md)의 "도메인을 바꿀 때"를 따른다. 이때 기존 도메인은 **그대로 서비스된다.**
3. **확인**: `curl -s https://<새 도메인>/actuator/health`가 `UP`이고, `sudo certbot certificates`에 두 도메인이 모두 보이는지 확인한다.
4. **외부 감시**: UptimeRobot에 새 도메인의 `/actuator/health` 모니터(5분 간격)를 **추가**한다. 옛 도메인 모니터는 아직 둔다.
5. **클라이언트**: 서버 주소(설정값)를 새 도메인으로 바꿔 배포한다. 이 서버 쪽에서는 할 일이 없고, 클라이언트 팀이 새 빌드를 낸다.
6. **전환 기간**: 옛 클라이언트가 새 도메인으로 모두 넘어갈 때까지 **두 도메인을 함께 서비스한다.** 기간은 클라이언트 배포·업데이트 속도로 정한다.
7. **옛 도메인 정리**(옛 클라이언트가 사라진 뒤): `server_name`에서 옛 도메인을 빼고 `nginx -t` 후 reload → 인증서를 새 도메인 하나로 다시 발급 → UptimeRobot의 옛 모니터 삭제 → DuckDNS 계정에서 서브도메인 삭제.

- 이 문서와 알림 대응 표의 `<도메인>`은 자리표시자라서 바꿀 값이 없다. 새 도메인의 실제 값은 저장소에 적지 않는다([CONTRIBUTING.md](../CONTRIBUTING.md)의 "인프라 식별 정보").
- 새 도메인 인증서도 갱신 실패 알림이 없다. 전환 뒤에도 3장의 만료일 점검을 유지한다.
- 도메인 등록 비용과 갱신일은 별도로 챙긴다(만료되면 접속이 끊긴다). 자동 갱신을 켜 두고 갱신일을 달력에 적는다.

## 부록: CloudWatch 알람을 다시 만들 때

알람은 CLI로 만들었다. 실제 값(`<인스턴스 ID>`, `<SNS 주제 ARN>`)은 콘솔에서 확인한다. 이름과 조건은 2장의 표와 같다. 한 예로 CPU 높음 알람은 다음과 같다.

```bash
aws cloudwatch put-metric-alarm --region ap-northeast-2 --alarm-name blackhole-ec2-cpu-high \
  --alarm-description "EC2 CPU above 80 percent for 15 min" \
  --namespace AWS/EC2 --metric-name CPUUtilization --dimensions Name=InstanceId,Value=<인스턴스 ID> \
  --statistic Average --period 300 --evaluation-periods 3 --datapoints-to-alarm 3 \
  --threshold 80 --comparison-operator GreaterThanThreshold --treat-missing-data notBreaching \
  --alarm-actions <SNS 주제 ARN> --ok-actions <SNS 주제 ARN>
```

- 알람 설명에는 **한글을 쓰지 않는다**(윈도 터미널에서 깨진다).
- 상태 체크 알람만 `--treat-missing-data breaching`이다(인스턴스가 중지되어 지표가 끊겨도 알리려는 의도).
