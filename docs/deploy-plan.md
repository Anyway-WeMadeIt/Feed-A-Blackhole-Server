# 배포 계획 (AWS EC2 + RDS + CI/CD)

> 상태: **계획 단계**. 아직 진행된 작업은 없다. 각 단계는 끝날 때마다 체크하고, 결정 사항은 4장에 기록한다.

## 1. 목표와 범위

- Spring Boot 서버를 **EC2**에, MySQL을 **RDS**에 올려 Unity 클라이언트가 인터넷으로 접속할 수 있게 한다.
- `main`에 병합하면 **테스트 → 빌드 → 배포**가 자동으로 이루어지게 한다(CI/CD).
- 범위 밖: 오토스케일링, 다중 서버, 무중단(blue/green) 배포, Kubernetes. 서버 1대로 시작하고 필요해지면 확장한다.

## 2. 현재 상태 (저장소 기준)

| 항목 | 현재 | 배포에서의 의미 |
| --- | --- | --- |
| 런타임 | Java 21, Spring Boot 4.1.1, Gradle Wrapper | `./gradlew bootJar`로 나온 jar를 JRE 21 기반 Docker 이미지로 감싼다. EC2에는 Docker만 있으면 된다. 저장소에 Dockerfile은 아직 없다. |
| DB | MySQL 8.4, Flyway(V1~V3), `ddl-auto=validate` | RDS도 **MySQL 8.4**로 맞춘다. 서버가 시작할 때 Flyway가 스키마를 자동 적용하므로 별도 마이그레이션 단계가 필요 없다. |
| 설정 | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` 환경변수로 덮어쓰기 가능 | 코드 수정 없이 주입만 하면 된다. 비밀값은 jar·저장소에 넣지 않는다. |
| 게임 콘텐츠 | `src/main/resources/content/`가 jar에 포함, 시작 시 검증 | 콘텐츠가 잘못되면 서버가 시작하지 않는다. 배포 실패의 흔한 원인이 될 수 있다. |
| CI | Checkstyle만 실행(`code-style.yml`) | 빌드·테스트 CI는 "후속 단계"로 README에 적혀 있다. 이번에 추가한다. |
| 헬스체크 | 없음(Actuator 미사용) | 배포 직후 "정상 기동"을 확인할 수단이 필요하다. 5장 1단계에서 추가한다. |
| 브랜치 | 기본 브랜치 `dev`, 배포 기준은 `main`(CONTRIBUTING 기준) | 어느 브랜치가 배포를 일으킬지 정해야 한다(4장). |

## 3. 목표 구조

```
Unity 클라이언트
      │ HTTPS (443)
      ▼
[EC2: Nginx(리버스 프록시) → Docker 컨테이너(Spring Boot, 8080)]   ← public subnet, 보안그룹 A
      │ MySQL (3306, 보안그룹 A에서 오는 것만 허용)
      ▼
[RDS MySQL 8.4]                                               ← private subnet, 외부 접근 불가

GitHub Actions: main 푸시 → 테스트 → 이미지 빌드 → ECR 푸시 → (SSM) EC2에서 pull·교체 → 헬스체크
```

핵심 원칙: **DB는 인터넷에서 직접 접근할 수 없게** 하고, EC2의 보안그룹에서 오는 연결만 받는다. 비밀값은 환경변수/Parameter Store로만 다룬다.

## 4. 결정해야 할 사항

| # | 질문 | 추천 | 대안 / 비고 |
| --- | --- | --- | --- |
| D1 | 배포 방식 | **확정: Docker 이미지 + ECR** (Actions가 이미지를 빌드해 ECR에 푸시하고, EC2가 pull해 컨테이너 교체) | jar + systemd, CodeDeploy는 채택하지 않음. |
| D2 | Actions → EC2 접속 방식 | **GitHub OIDC + SSM Run Command** (SSH 포트·장기 키 불필요). SSM으로 EC2에서 `docker pull`·재시작을 실행한다. | SSH 키를 GitHub Secrets에 저장하는 방식이 더 단순하지만 22번 포트를 열어야 하고 키 관리가 필요하다. |
| D3 | HTTPS | **도메인 + Nginx + Let's Encrypt(certbot)** | ALB + ACM은 관리가 편하지만 월 고정비가 든다. Unity 빌드는 HTTP 호출을 막는 경우가 많아 HTTPS는 사실상 필수다. 도메인이 없으면 먼저 확보해야 한다. |
| D4 | 배포 트리거 브랜치 | `main` 푸시 | `dev`를 스테이징에 자동 배포하는 2환경 구성도 가능하지만 비용·복잡도가 2배다. |
| D5 | 리전 | `ap-northeast-2`(서울) | |
| D6 | 환경 수 | 운영 1개 | 데모·평가용이면 충분. |
| D7 | 비용 한도 | 월 예산 알림 설정 후 시작 | 현재 계정은 **무료 플랜(크레딧 기반)** 이다. 크레딧이 소진되거나 무료 기간이 끝나면 서비스가 중단될 수 있으므로 크레딧 잔액을 주기적으로 확인한다(2026-10-15 기준 잔액 약 US$157). |
| D8 | CPU 아키텍처 | **x86_64** (이미지는 `linux/amd64`로 빌드, EC2는 `t3` 계열) | ARM(Graviton, `t4g`)은 시간당 약 20% 저렴하지만 이미지를 `linux/arm64`로 빌드해야 해서 CI에 ARM 러너나 멀티 플랫폼 빌드가 필요하다. 첫 배포는 변수를 줄이기 위해 x86으로 가고, 안정화 후 전환을 검토한다. Dockerfile은 두 아키텍처 모두에서 빌드 가능하다. |

## 5. 단계별 작업

각 단계는 **그 단계가 끝났음을 확인하는 방법(검증)** 을 함께 적는다.

### 0단계. AWS 계정 준비 (콘솔)
- 2026-10-15: 기존 실습 리소스(RDS, 스냅샷, VPC, IAM 사용자 `s3-camp-health-user` 등)를 모두 정리했다. 현재 IAM 사용자는 0개이고 루트로 로그인해 있다.
- [x] 루트 MFA 설정 완료, 루트 액세스 키 없음(확인)
- [x] 작업용 IAM 사용자 `gwangmin` 생성 완료: 그룹 소속(`admins`), 콘솔 로그인 + 가상 MFA, 액세스 키 없음(확인). 배포 관리자는 1명이며, 이후 조회 전용 팀원이 생기면 `viewers` 그룹(`ReadOnlyAccess`)을 추가한다.
- [x] 예산 알림: 기존 `My Monthly Cost Budget`(월 US$30, 2026-10-19에 US$50으로 변경, 실제 85%·100%/예상 100% 초과 시 `p4ngmin@gmail.com`으로 알림)을 그대로 사용한다. 이 예산은 크레딧 차감 전 비용 기준이다.
- [ ] 리전을 서울로 고정(작업 시 콘솔 우측 상단 리전이 `ap-northeast-2`인지 확인)
- 검증 결과(2026-10-15): 루트가 아닌 IAM 사용자로 로그인되고 예산 알림이 설정되어 있다.

### 1단계. 서버를 배포 가능한 상태로 만들기 (코드 변경, PR 1개)
- [x] `spring-boot-starter-actuator` 추가 + `/actuator/health`만 인증 없이 열기, 상세 정보는 비공개
- [x] 운영용 설정 정리: `server.shutdown=graceful`(대기 20초), `forward-headers-strategy=framework`. 별도 `application-prod.properties`는 필요하지 않아 만들지 않았다.
- [ ] RDS 접속용 JDBC URL 파라미터 확인(`sslMode`, 시간대 등) — RDS 엔드포인트가 정해지는 2~3단계에서 `DB_URL` 형식을 확정한다.
- [ ] `JWT_SECRET` 운영용 값 신규 생성(개발용과 다른 값) — 저장소에 남기지 않도록 2단계에서 Parameter Store에 직접 넣을 때 만든다.
- [x] `Dockerfile` 작성: 멀티 스테이지(빌드: JDK 21 + `bootJar` → 실행: JRE 21 + jar), 비루트 사용자로 실행, `.dockerignore`로 `.env`·`.git`·`.idea`·`build/` 제외(비밀값이 이미지에 들어가지 않게)
- 검증 결과(2026-10-15, 로컬): `./gradlew build` 통과(테스트 361개). 이미지를 임시 MySQL에 붙여 실행해 Flyway V1~V3 적용과 헬스체크 `UP`을 확인했고, DB를 끊으면 503(`DOWN`), 종료 신호에는 graceful shutdown 로그를 확인했다. 이미지에 `.env`가 없고 비루트로 실행됨을 확인했다.
- 참고: DB가 끊긴 상태에서 헬스체크가 `DOWN`을 응답하기까지 약 30초가 걸린다(DB 연결 대기 기본값). 6단계의 배포 스크립트는 헬스체크 타임아웃을 40초 이상으로 잡는다.

### 2단계. AWS 인프라 수동 구축 (콘솔로 한 번 직접 만들어 본다)
처음에는 콘솔로 손수 만들어 각 구성요소의 역할을 이해하고, 안정되면 IaC(Terraform 등)는 선택 과제로 둔다.
진행 기록(2026-10-15, 모두 서울 리전). 리소스 이름은 `blackhole-` 접두사를 쓴다.
이 저장소는 공개라서 **계정 ID, 엔드포인트, IP, 리소스 ID는 이 문서에 적지 않고** `<…>` 자리표시자로 쓴다(실제 값은 AWS 콘솔의 해당 리소스 화면에서 확인한다). 이 기준은 `scripts/check-identifiers.sh`(CI의 Identifier check)가 자동으로 검사한다.
- [x] 네트워크: 서울 리전에는 기본 VPC가 없어 **VPC를 새로 만들었다**(`blackhole-vpc`, 10.0.0.0/16, 2개 AZ, 퍼블릭 2 + 프라이빗 2, 인터넷 게이트웨이, S3 게이트웨이 엔드포인트, **NAT 없음**). DB 서브넷 그룹 `blackhole-db-subnet-group`은 프라이빗 서브넷 2개(2a, 2b)로 만들었다. EC2는 퍼블릭 서브넷(2a)에 둔다.
- [x] 보안그룹: `blackhole-sg-app`(80/443 전체 허용, SSH 없음), `blackhole-sg-db`(3306은 `blackhole-sg-app`에서만 허용)
- [x] RDS `blackhole-db`: MySQL 8.4.11, `db.t4g.micro`, 단일 AZ, 퍼블릭 액세스 끔, 암호화, 삭제 방지, 초기 DB `feedablackhole`, 마스터 사용자 `blackhole`. 엔드포인트 `<RDS 엔드포인트>`.
  - 무료 플랜 제한으로 **자동 백업 보존은 1일**(7일은 거부됨), 프리 티어 템플릿·단일 AZ로 고정된다. 기본 파라미터 그룹 `default.mysql8.4`가 문자셋 utf8mb4·시간대 UTC라 별도 파라미터 그룹은 만들지 않았다.
  - 마스터 계정은 관리용으로만 쓰고, **앱 전용 DB 계정을 따로 만든다**(아래 3단계 전 작업).
- [x] ECR `feedablackhole-server`(태그 변경 불가, AES-256), 수명 주기 규칙 "이미지 10개 초과 시 오래된 것 만료". URI `<계정ID>.dkr.ecr.ap-northeast-2.amazonaws.com/feedablackhole-server`. 푸시 시 스캔은 리포지토리 설정이 사라지고 레지스트리 단위로 옮겨져 설정하지 않았다.
- [x] IAM: 정책 `blackhole-read-parameters`(SSM 파라미터 읽기. 리소스는 `parameter/blackhole`와 `parameter/blackhole/*` 둘 다 필요하다. `get-parameters-by-path`는 경로 자체를 리소스로 검사하므로 `/*`만 허용하면 거부된다), 역할 `blackhole-ec2-role`(+ `AmazonSSMManagedInstanceCore`, `AmazonEC2ContainerRegistryReadOnly`)
- [x] EC2 `blackhole-app`(`<인스턴스 ID>`): Amazon Linux 2023 x86_64, `t3.small`(크레딧 표준), `blackhole-sg-app`, 20GiB gp3 암호화, 키 페어 없음(SSM으로만 접속), IMDSv2 필수, 종료 방지, 사용자 데이터로 Docker 설치·로그 10MB×3 제한. 탄력적 IP `<탄력적 IP>` 연결.
- [x] Parameter Store(SecureString, 키 `alias/aws/ssm`) `/blackhole/DB_URL`(`...:3306/feedablackhole?sslMode=REQUIRED`), `/blackhole/DB_USERNAME`(`blackhole_app`), `/blackhole/DB_PASSWORD`, `/blackhole/JWT_SECRET`. 비밀값 두 개는 직접 입력했고 비밀번호 관리자에도 저장했다.
- [x] 앱 전용 DB 계정 `blackhole_app`@`10.0.%`: `feedablackhole` 스키마에 SELECT/INSERT/UPDATE/DELETE/CREATE/ALTER/DROP/INDEX/REFERENCES만 부여했다(Flyway가 DDL을 실행하므로 DDL 권한 포함, 서버 전체 권한 없음). 마스터 계정은 관리용으로만 쓴다. 비밀번호는 `/blackhole/DB_PASSWORD`와 동일한 값이며 SQL에는 터미널이 Parameter Store에서 읽어 넣었다.
- 2단계 진행 중 확인한 사항: EC2에서 ECR 로그인 성공, 인스턴스 역할 인식, SSM 관리 대상 등록 확인. 내 PC에서 RDS 3306과 EC2의 22·8080·3306에 접속되지 않음을 확인했다.
- 이전 실습의 잔여 리소스(미정리): IAM 역할 `test-ec2-ssm-readonly`, RDS 서브넷 그룹 `my-vpc-private-subnet-group`·`my-vpc-public-subnet-group`, Parameter Store `/webcraft/prod/*`(평문 String으로 저장된 `DB_PASSWORD` 포함).
- 검증: EC2에서 RDS에 접속되고, 내 PC에서는 RDS에 접속되지 않는다. EC2에서 `aws ecr get-login-password`로 ECR 로그인이 된다. SSM Session Manager로 EC2에 접속된다.

### 3단계. 수동 배포로 한 번 끝까지 해 보기
자동화 전에 사람이 하는 절차를 먼저 겪어야 자동화할 내용을 정확히 알 수 있다.
진행 기록(2026-10-19 기록). 자세한 절차와 명령은 `devlog-102`(개발 일지)에 정리했다.
- [x] 내 PC에서 이미지 빌드 → ECR 푸시. 태그는 수동 연습용으로 `m1`, `m2`를 썼다(ECR이 태그 변경 불가라 같은 태그를 다시 올릴 수 없다). 6단계 자동화부터는 커밋 SHA 같은 고유 태그를 쓴다.
  - 로컬 AWS 자격은 장기 액세스 키 대신 `aws login`(콘솔 로그인으로 받는 임시 자격)을 쓴다.
  - 빌드는 `docker build --platform linux/amd64 --provenance=false`. `--provenance=false`는 ECR에 어테스테이션 항목이 별개 이미지처럼 섞이는 것을 막는다.
- [x] EC2에서 Parameter Store 값을 읽어 env 파일(`umask 077`, 권한 600)로 만들고, `docker pull` 후 `docker run -d --restart unless-stopped -p 127.0.0.1:8080:8080 --env-file ...`로 실행한 뒤 env 파일은 바로 삭제한다. 앱 포트는 서버 내부(`127.0.0.1`)에만 열어 4단계의 Nginx만 접근하게 한다.
- [x] 컨테이너 시작 → Flyway가 앱 전용 계정(`blackhole_app`)으로 RDS에 V1~V3을 적용하는 것을 `docker logs`로 확인했다. 앱 계정의 DDL 권한이 충분함이 확인됐다.
- [x] 같은 이미지에 새 태그(`m2`)를 붙여 올려, 새 태그로 교체와 이전 태그(`m1`)로 되돌리기를 손으로 해 봤다.
- [x] EC2를 재부팅해도 컨테이너가 다시 뜬다(사용자 데이터의 Docker 부팅 시 자동 시작 + `--restart unless-stopped`).
- [x] 이 절차를 스크립트 `deploy/deploy.sh <태그>`로 정리했다(`sudo bash deploy.sh <태그>`로 실행, 종료 코드 0이면 성공). 순서: ① Parameter Store 설정 검증(기존 컨테이너를 내리기 전에 필수 4개 키와 `JWT_SECRET` 32자 이상 확인) → ② 임시 폴더로 ECR 로그인 후 이미지 받기 → ③ `docker stop -t 30`으로 교체 → ④ 헬스체크 통과 시 태그를 `/var/lib/blackhole/last-good-tag`에 기록, 실패 시 마지막 정상 태그로 롤백(이번 배포는 실패로 처리해 종료 코드 1). 계정 ID는 `sts get-caller-identity`로 조회해 저장소에 적지 않는다. `flock`으로 동시 실행을 막는다.
  - 가짜 `docker`/`aws`/`curl`로 분기를 돌려 보는 시나리오 테스트(37개)를 통과했다: 첫 배포, 교체, 크래시·헬스체크 DOWN 시 롤백, 설정 누락·짧은 JWT·잘못된 태그·pull 실패 시 기존 컨테이너 유지, 되돌릴 대상이 없는 첫 배포 실패. 스크립트를 일부러 망가뜨리면 테스트가 실패하는 것도 확인했다.
- [x] EC2에서 `deploy.sh`로 실제 교체와 롤백을 1회씩 검증했다(2026-10-19). 롤백은 곧바로 종료하는 `alpine` 이미지를 `bad1` 태그로 ECR에 올려 유도했다.
  - 성공 경로: 이미 실행 중이던 `m2`를 다시 배포(`m2 -> m2`)해 교체 메커니즘을 확인했다. `배포 성공`, 종료 코드 0, 교체 시작부터 헬스체크 통과까지 약 16초.
  - 롤백 경로: `bad1`은 컨테이너가 `restarting` 상태가 되어 4초 만에 실패로 판정됐고, 직전 정상 태그 `m2`로 되돌렸다(`되돌리기 성공`, 종료 코드 1). 교체 시작부터 `m2`가 다시 정상이 되기까지 약 20초.
  - 새 태그(`m3`)를 올리는 검증은 하지 않았다. 같은 태그 재배포로 정상 경로를 확인했다.
  - ECR에는 `bad1`(alpine) 이미지가 남아 있다. 수명 주기 규칙(10개 초과 시 만료)이 정리하므로 그대로 둬도 된다.
  - 로컬의 가짜 도구 테스트에서 예상한 대로, 실제 `docker`·`curl` 환경에서도 같은 분기로 동작했다. SSM Run Command로 실행하는 검증은 6단계에서 한다.
- 검증: EC2 내부에서 `curl localhost:8080/actuator/health`가 `UP`이고 토큰 없이 보호 경로를 호출하면 `401`이다. `docker logs`에서 Flyway V1~V3 적용 로그를 확인했다.
- 6단계(`deploy.sh`·CD)에 반영할 점:
  - 교체는 `docker stop -t 30`으로 한다. `docker stop`의 기본 대기 시간(10초)이 앱의 graceful shutdown 대기(20초)보다 짧아 처리 중인 요청이 강제 종료될 수 있다.
  - ECR 로그인은 배포할 때마다 하고 끝나면 로그아웃한다(`docker login`이 토큰을 `config.json`에 평문으로 저장한다).
  - env 파일은 배포 때마다 Parameter Store에서 만들고 `docker run` 직후 지운다. 컨테이너 설정에는 값이 남으므로 `docker inspect` 출력은 공유하지 않는다.
  - 교체 전에 직전 정상 태그를 기록해 두고, 헬스체크(타임아웃 40초 이상)가 실패하면 그 태그로 되돌린다.

### 4단계. 도메인과 HTTPS
도메인은 두 단계로 간다. 먼저 **임시 무료 도메인**(예: DuckDNS)으로 HTTPS까지 동작을 검증하고, 팀원과 논의해 **정식 도메인을 구매하면 교체**한다. 교체가 쉽도록 서버 주소를 코드에 고정하지 않는다(클라이언트의 서버 주소는 설정값으로 둔다).
진행 기록(2026-10-19). 도메인은 `<도메인>`으로 적는다.
- [x] (임시) DuckDNS 서브도메인을 발급받아 A 레코드를 EC2 탄력적 IP(`<탄력적 IP>`)로 연결했다(`nslookup`으로 확인). 계정 가입·로그인은 직접 했다.
- [x] Nginx 설치, 443 → 8080 프록시(`server_name`에 도메인 지정). 앱 포트 8080은 외부에 열지 않는다(`127.0.0.1`에만 바인딩, 3단계에서 적용 완료). 프록시가 `Host`, `X-Forwarded-For`, `X-Forwarded-Proto`를 넘겨야 앱의 `forward-headers-strategy=framework`가 동작한다.
- [x] certbot으로 인증서 발급·자동 갱신. Amazon Linux 2023 저장소에 certbot이 있어 `dnf install -y nginx certbot python3-certbot-nginx`로 설치하고(`certbot plugins`로 nginx 플러그인 확인), 갱신은 `certbot-renew.timer`를 켜서 처리한다. 순서: ① `--dry-run`(스테이징에서 시뮬레이션, 발급 한도를 쓰지 않음)으로 DNS·80 포트·설정 검증 → ② 실제 발급 → ③ `certbot renew --dry-run`으로 갱신 경로 검증.
  - 위험: Let's Encrypt는 "등록 도메인당 발급 횟수" 한도가 있는데, 공용 무료 도메인(`duckdns.org`)이 공개 접미사 목록(PSL)에 있는지 확인하지 못했다. 목록에 없으면 같은 도메인을 쓰는 다른 사용자와 한도를 나눠 발급이 거부될 수 있다. 그래서 반드시 `--dry-run`을 먼저 하고, 거부되면 정식 도메인 구매를 앞당긴다. 이번에는 거부 없이 발급됐다.
  - 이메일 없이 발급했다(`--register-unsafely-without-email`). Let's Encrypt가 2025-06-04에 만료 알림 메일 발송을 종료해서 이메일은 선택 사항이다. 대신 **갱신이 실패해도 알림이 오지 않으므로** `sudo certbot certificates`로 만료일을 직접 확인한다(7단계에서 알람 추가). 갱신은 HTTP-01 방식이라 **보안그룹의 80 포트와 Nginx의 HTTP 서버 블록을 닫지 않는다.**
  - 인증서 유효 기간: 2026-10-18 발급, 2027-01-16 만료(90일). 갱신은 만료 30일 전부터 타이머가 시도한다.
- [ ] (정식) 도메인 구매 후 같은 탄력적 IP로 A 레코드 연결 → Nginx `server_name`과 인증서에 새 도메인 추가 → 클라이언트 서버 주소 변경·배포. **전환 기간에는 두 도메인을 함께 서비스**하고, 구 클라이언트가 사라지면 임시 도메인을 제거한다. 당분간은 DuckDNS를 유지하기로 했다(2026-10-19). 전환 절차(외부 감시·옛 도메인 정리 포함)는 [runbook.md](runbook.md) 9장 "정식 도메인으로 바꿀 때"에 있고, 도메인은 앱 코드·CD·Parameter Store에 들어 있지 않아 서버 재배포는 필요 없다.
- [x] 검증: 내 PC에서 `curl https://<도메인>/actuator/health`가 성공하고, 클라이언트 접속 주소를 이 도메인으로 바꿔 로그인과 게임 진행이 동작한다. 외부에서 읽기 전용으로 추가 확인한 결과(2026-10-19):
  - `http://…` 요청은 301로 `https://…`에 리다이렉트된다. 헬스체크는 `UP`, 토큰 없는 `/api/v1/me`·`/actuator/env`·`/actuator/info`는 401이다.
  - 인증서는 Let's Encrypt 발급이고 TLS 1.2·1.3이 동작한다(TLS 1.0·1.1 거부 여부는 로컬 OpenSSL이 시도 자체를 막아 확인하지 못했다). 8080·22 포트는 외부에서 닫혀 있다.
  - `Strict-Transport-Security`, `X-Content-Type-Options`, `X-Frame-Options` 헤더는 **앱(Spring Security)이 보낸다.** 그래서 Nginx에 HSTS를 또 추가하면 헤더가 중복된다.
- 남은 선택 항목(필수는 아님):
  - [x] `server_tokens off;`로 응답 헤더의 Nginx 버전 노출을 껐다(`Server: nginx`로 확인).
  - [x] 알 수 없는 Host의 HTTPS 요청을 앱까지 넘기지 않는다. 적용 전에는 IP나 다른 이름으로 접속해도 Nginx가 앱으로 프록시해서(401) 스캐너 트래픽이 앱에 닿았다. 443에 `ssl_reject_handshake on;`인 기본 서버를 두었고(`deploy/nginx/01-default-reject.conf`), IP 직접 접속이나 다른 이름의 SNI는 핸드셰이크가 거부된다(curl 종료 코드 35).
    - SNI는 우리 도메인이고 `Host`만 다른 요청은 핸드셰이크를 통과해서, 처음에는 Nginx 기본 환영 페이지(200)를 받았다. 예상했던 404가 아니었는데, 이 서버 블록에 `root`가 없어 기본 문서 폴더를 서비스하기 때문이다. `return 444;`를 추가해 지금은 응답 없이 연결이 닫힌다(HTTP 코드 `000`, curl 종료 코드 56).
    - HTTP는 80 포트의 certbot 리다이렉트 블록이 알 수 없는 Host를 `return 404`로 막는다.
  - [x] `/etc/letsencrypt/options-ssl-nginx.conf`의 `ssl_protocols`가 TLS 1.2 이상인지 서버에서 확인한다.
  - [x] Nginx 설정을 저장소에 두었다(`deploy/nginx/`): 사이트 설정(certbot 적용 전 모양, 도메인은 `<도메인>`), 하드닝 파일 두 개, 설치부터 인증서·하드닝·검증·되돌리기·도메인 교체까지의 절차를 담은 `README.md`. 서버에는 이 문서의 순서(사이트 설정 → 인증서 → 하드닝)대로 이미 적용돼 있다.
  - [x] 인증 경로(`/api/v1/auth/`)의 요청 빈도 제한(`limit_req`): [`deploy/nginx/02-rate-limit.conf`](../deploy/nginx/02-rate-limit.conf)를 서버에 적용했다(2026-10-20)(register IP당 분당 10회·burst 20, 인증 경로 분당 60회·burst 30). 운영 서버에서 확인: `register`는 21번째부터 `429`(앞의 21개는 `201`), 인증 경로 `refresh`는 31~38개 뒤부터 `429`, 헬스체크와 `/api/v1/me`는 영향 없음, 70초 뒤 정상 복귀. 시험으로 테스트 계정 23개가 생겼고 다음 DB 초기화 때 지운다. 게스트 등록은 토큰 없이 DB 행을 만들기 때문이다. 모바일 통신사 공유 IP를 고려해 한도는 넉넉하게 잡는다.
- 참고: JWT 발급자(`app.jwt.issuer`)는 URL이 아니라 고정 문자열이라 도메인을 바꿔도 발급된 토큰이 무효화되지 않는다. 무료 도메인은 가용성과 인증서 발급 한도를 보장하지 않으므로 정식 출시에는 쓰지 않는다.

### 5단계. CI 확장 (PR 단계)
- [x] 빌드·테스트 CI는 팀이 이미 `.github/workflows/test.yml`(Tests, 잡 이름 `Test`)로 구현했다. `./gradlew test`를 PR과 `main` 푸시에서 실행하고, ubuntu 러너의 Docker로 Testcontainers가 동작한다. 별도 `ci.yml`은 만들지 않는다.
- [x] Gradle 캐시는 `test.yml`의 `setup-java`(`cache: gradle`)로 이미 적용돼 있다.
- [x] GitHub 브랜치 보호: `main`에 Checkstyle·빌드(Test)·식별 정보 검사(Identifier check) 통과 필수, 리뷰 1명 이상(CONTRIBUTING에 이미 명시된 방침). 새 검사는 워크플로가 한 번 실행된 뒤에야 필수 검사 목록에 나타난다.
- [x] 검증: `main` 대상 PR에서 일부러 실패시킨 테스트와 가짜 식별 정보가 각각 `Test`, `Identifier check` 실패로 표시되고 병합 버튼이 막히는 것을 확인했다(검증용 브랜치와 PR은 병합하지 않고 닫는다).

### 6단계. CD (main 병합 시 자동 배포)
- [x] AWS에 GitHub OIDC 공급자와 배포용 IAM 역할(`blackhole-github-deploy`)을 만들었다. 신뢰 정책은 `aud`와 `sub`(이 저장소의 `production` 환경)를 정확히 일치시키고, 권한은 ECR 푸시(리포지토리 한정, `DescribeImages` 포함)와 해당 인스턴스에 대한 `AWS-RunShellScript` SSM 실행으로 한정했다.
- [x] GitHub Environment `production`: 승인자 지정, 배포 브랜치는 `main`만. 역할 ARN과 인스턴스 ID는 이 환경의 **시크릿**(`AWS_ROLE_ARN`, `EC2_INSTANCE_ID`)에 둔다. 저장소와 로그가 공개라서 값이 로그에서 가려지게 하려는 것이다. 승인 단계가 있으면 OIDC 토큰의 `sub`가 브랜치가 아니라 환경 이름(`environment:production`)이 되므로, 신뢰 정책도 그에 맞춘다.
- [x] 워크플로 `deploy.yml`(`main` 푸시 트리거, 작성 완료·첫 실행 검증 전): 테스트 → 이미지 빌드(태그: 커밋 SHA) → ECR 푸시 → SSM Run Command로 EC2의 `deploy.sh <태그>` 실행 → 헬스체크 → 실패 시 자동으로 이전 태그로 롤백
  - 교체·헬스체크·롤백 로직은 `deploy/deploy.sh`에 있다. 워크플로는 이 스크립트를 SSM으로 EC2에서 실행하고 **종료 코드와 출력**만 본다. 스크립트가 EC2에 있어야 하므로 워크플로가 실행 직전에 저장소의 파일 내용을 SSM 명령으로 전달하는 방식을 쓴다.
- [x] 직전에 정상 동작한 태그를 EC2에 기록해 두어 롤백 대상으로 쓴다: `deploy.sh`가 `/var/lib/blackhole/last-good-tag`에 기록한다. 다만 ECR 수명 주기 규칙(최근 10개 유지)이 그 이미지를 지우지 않는지는 남은 확인 사항이며, 스크립트는 이미지가 로컬에 없으면 다시 받는다.
- [x] 배포 동시 실행 방지(`concurrency`, 진행 중인 배포는 취소하지 않고 대기). [ ] 실패 시 알림은 GitHub 기본 메일을 쓴다(첫 실패 때 수신 여부를 확인한다).
- 검증: 사소한 변경을 `main`에 병합하면 사람 손 없이 배포되고, 일부러 헬스체크가 실패하는 변경을 넣으면 롤백된다.

### 7단계. 운영 최소 점검
- [x] 로그 확인법과 디스크 정리 기준은 [runbook.md](runbook.md) 5장·3장에 정리했다(컨테이너 로그 크기 제한 10MB×3, `deploy.sh`가 7일 지난 미사용 이미지를 정리). [ ] CloudWatch Logs로의 전송은 아직 하지 않았다(배포로 이전 컨테이너가 삭제되면 그 로그가 사라지는 빈틈이 있다. 사용자가 생기면 검토).
- [x] 알림과 감시(2026-10-19): SNS 표준 주제 `blackhole-alerts`(이메일 구독, 확인됨)에 CloudWatch 알람 7개를 연결했다. EC2 상태 체크 실패, EC2 CPU 높음, EC2 CPU 크레딧 부족, RDS 저장공간·메모리·CPU 크레딧·연결 수. 알림 경로는 시험 알림으로 확인했다(알람이 처음 `OK`가 될 때 복귀 메일이 알람마다 한 통씩 오는 것도 정상이다). 외부 가동 감시는 UptimeRobot으로 `/actuator/health`를 5분마다 확인한다. (2026-10-21에 `blackhole-rds-burst-balance-low`(gp2 버스트 크레딧 30% 미만)를 더해 8개가 되었고, 여유 메모리 알람 기준은 100MiB에서 50MiB로 낮췄다.)
- [x] RDS 백업 복구 절차 한 번 시험(스냅샷에서 복원): 2026-10-19 수동 스냅샷 → 새 인스턴스 복원(약 8분) → 서버에서 앱 계정으로 접속해 Flyway 버전과 테이블 확인 → 복원본과 스냅샷 삭제. 절차는 [runbook.md](runbook.md) 6장
- [x] 운영 런북 작성: [runbook.md](runbook.md)(알림 대응, 배포·롤백, 로그, DB 접속·스냅샷, 비밀값 교체, 비용·일시 중지). 실제로 해 보지 않은 절차(스냅샷 복구, 비밀번호 교체, 일시 중지)는 `미검증`으로 표시했고, 해 본 뒤에 고친다.
- [x] 부하 테스트(로컬, 2026-10-21): [loadtest/](../loadtest/README.md)에 k6 시나리오·로컬 시험 환경·측정 스크립트를 두었다. 프로파일링으로 앱 병목 4곳(트랜잭션마다 `SET autocommit` 2번, RUN_START마다 자동 flush, `sync_log` 로그별 INSERT, 운영에서 G1 GC가 될 가능성)을 찾아 고쳐 요청당 앱 CPU를 줄였다(요청당 로그 2/50/500개: 11.5/43/349 → 6.6/22/184ms, 충분히 예열한 로그 1개 요청은 −16%). 한계는 크레딧 있음 약 300건/초(앱 2코어 포화), 크레딧 소진 50~80건/초(예열 상태에 좌우)로 개선 전후가 같았고, 사용자당 초당 0.097로그로 환산하면 약 3,000명/500~800명이다. 악의적 시나리오(로그 500개를 초당 2건)는 앱을 포화시키고 `sync_log`가 시간당 약 3GB 늘어 디스크가 6~7시간 안에 찬다는 것도 확인했다.
- [x] 부하 테스트(운영 환경, 2026-10-21): 개선 전 이미지와 개선 후 이미지를 같은 서버에 번갈아 배포해 같은 절차로 비교했다(Nginx 요청 제한은 시험 동안만 해제). 정상 규모(동시 50명·분당 20건, 초당 약 16건)와 초당 20~160건은 모두 p95 25~35ms·오류 0이었다. 같은 부하(초당 200건 6분)에서 요청당 앱 CPU는 4.30 → 3.35ms(−22%)였지만 p95 500ms를 통과한 마지막 단계(320건/초)와 포화가 시작된 단계(400건/초)는 같았고, **병목은 앱 → Nginx·Docker 연결 → RDS 스토리지 순으로 옮겨 갔다.** 서버 CPU 포화의 원인은 Nginx가 앱으로 요청마다 새 연결을 만드는 것(TIME_WAIT 8,194개, docker-proxy CPU 25%)이었고, 업스트림 keepalive 적용 뒤 320건/초 p95가 231~323 → 105ms, 400건/초 밀림이 738~1,162 → 0이 되었다. 그다음 병목은 **RDS gp2 20GiB의 보장 IOPS 100**: 시험으로 쓰기 IOPS 최대 2,298, `BurstBalance` 99% → 46%였고, 요청당 쓰기 IOPS 약 7~12로 계산하면 지속 용량은 초당 약 8~10건(계속 활동하는 사용자 약 85~100명)이다. keepalive 적용 후의 정확한 천장은 RDS 크레딧을 아끼려고 찾지 않았고(400건/초는 기준 초과였지만 밀림·오류 없이 모두 처리, p95 744ms). 이후 가르는 시험에서 부하 생성기(k6를 둘로 나눠도 같은 지연), 서버 CPU(유휴 51~91%), Nginx·Docker 연결, DB 스토리지·CPU(쓰기 IOPS 최대 1,217, 쓰기 지연 0.82ms, RDS CPU 33%)는 한계가 아님을 확인했고, 앱–DB 접속·왕복 구간(풀 10개, 가용영역 간 지연)이 유력하지만 풀 크기를 바꿔 확인하지는 못했다, 크레딧 소진 상태는 운영에서 재지 못했다. 절차와 수치는 [loadtest/README.md](../loadtest/README.md).
- [x] Nginx 업스트림 keepalive: 서버에 2026-10-21 적용(백업 `/etc/nginx/blackhole.conf.bak-loadtest`), 저장소의 `deploy/nginx/blackhole.conf`·README에도 반영(PR).
- [ ] DB 접속 풀 크기 10 → 30 시험(설정 변경, `spring.datasource.hikari.maximum-pool-size`): 같은 400건/초를 다시 쏴서 p95가 크게 내려가고 완료율이 400건/초에 이르면 풀이 한계, 그대로면 DB 안쪽이다. RDS 최대 연결(약 85)과 연결 수 알람(50 초과)을 고려하고, 크레딧(`BurstBalance`·EBS I/O 잔량)이 회복된 뒤에 한다. 같은 가용영역 배치 효과도 함께 본다.
- [ ] RDS 스토리지를 gp2 → gp3로 변경 검토: gp3는 20GiB에서도 기본 3,000 IOPS이고 가격이 비슷하다. 무료 플랜에서 허용되는지, 변경 중 성능 영향(수 시간, 이후 6시간은 재변경 불가)을 확인한 뒤 결정한다.
- [x] CloudWatch 알람(2026-10-21): `blackhole-rds-burst-balance-low`(`BurstBalance` 30% 미만, 5분 최저값, 기존 SNS 주제 연결)를 추가했고, RDS 여유 메모리 알람 기준을 100MiB에서 50MiB로 낮췄다(평소 값이 98~138MiB라 오탐 가능성이 컸다). 생성 명령은 [runbook.md](runbook.md) 부록.
- [ ] 계정별 요청·로그 처리량 상한과 `sync_log` 정리(후속 과제)
- [x] DB 초기화·관리 절차(2026-10-19): 테스트 계정 전체 초기화, 계정 하나 삭제, 스키마까지 초기화(마이그레이션을 고쳐야 할 때), DB 규칙을 [runbook.md](runbook.md) 6장에 정리했다. SQL은 로컬 MySQL 8.4에서 먼저 확인했고, 전체 초기화는 운영 RDS에서 실행해 오류 없이 끝났다(앱을 멈추고 삭제한 뒤 다시 켬). 계정 하나 삭제와 스키마까지 초기화는 서버에서 아직 실행하지 않았다(`미검증`). 초기화 뒤 클라이언트가 게스트를 다시 등록하는지는 확인이 필요하다.
- [x] 비용 확인 주기와 한도(2026-10-19): 월 예산을 US$30에서 **US$50**으로 올렸다(알림 구성은 그대로, 크레딧 차감 전 비용 기준). 비용 이상 탐지 2건은 10/15 새 EC2와 디스크 생성에 따른 정상 변동이었다. 현재 구성의 하루 사용액은 약 US$1.49(월 US$45 안팎)이고 크레딧 잔액은 US$133.99라서 **2027년 1월 중순에 소진**될 것으로 본다(만료일 2027-03-23보다 이르다). 크레딧 잔액은 월 1회 확인하고 소진 한 달 전에 계속 운영·중지·유료 전환을 정한다([runbook.md](runbook.md) 8장). 중지 가능 리소스(EC2, RDS)와 절차는 런북에 `미검증`으로 있다.

## 6. 위험과 주의점

- **Flyway 마이그레이션은 되돌릴 수 없다.** 이미 병합된 `V*.sql`은 수정 금지(README 규칙). 스키마를 바꾸는 배포를 롤백하려면 이전 jar가 새 스키마에서도 동작해야 한다. 컬럼을 지우는 변경은 2번에 나눠 배포한다.
- **`JWT_SECRET`을 바꾸면 모든 액세스 토큰이 무효**가 된다. 운영 키 교체는 신중히.
- **게임 콘텐츠 검증 실패 = 서버 미기동.** 콘텐츠 갱신 PR이 CI(테스트)에서 걸러지는지 5단계에서 확인한다.
- RDS를 퍼블릭으로 열거나 `0.0.0.0/0`을 3306에 허용하지 않는다. 비밀값은 저장소·로그·PR에 절대 남기지 않는다. 로컬 `.env`는 배포물에 포함되지 않는다.
- 서버 1대 구성이라 **배포 중 수 초간 접속이 끊긴다.** 현재 규모에서는 허용한다. 클라이언트의 재시도 동작은 별도로 확인한다.
- 이미지는 ECR에 쌓이면 저장 비용이 들고, EC2 디스크도 차지한다. 수명 주기 규칙과 주기적 정리를 둔다. 이미지에는 비밀값을 넣지 않는다(`.dockerignore`, 런타임 주입).
- 프리티어·요금은 시점마다 달라지므로 시작 전 AWS 가격 페이지로 확인한다.

## 7. 진행 순서 제안

`0 → 1 → 2 → 3`까지가 "수동으로라도 서버가 인터넷에서 돈다"는 첫 마일스톤이다. 이후 `4`(HTTPS), `5·6`(CI/CD), `7`(운영) 순서로 진행한다. 5단계(CI 확장)는 1단계와 병행해도 된다.
