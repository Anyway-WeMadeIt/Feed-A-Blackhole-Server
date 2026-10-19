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
- [x] 예산 알림: 기존 `My Monthly Cost Budget`(월 US$30, 실제 85%·100%/예상 100% 초과 시 `p4ngmin@gmail.com`으로 알림)을 그대로 사용한다. 이 예산은 크레딧 차감 전 비용 기준이다.
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
- [ ] (임시) 무료 서브도메인을 발급받아 A 레코드를 EC2 탄력적 IP(`<탄력적 IP>`)로 연결. 계정 가입·로그인은 직접 한다.
- [ ] Nginx 설치, 443 → 8080 프록시(`server_name`에 도메인 지정). 앱 포트 8080은 외부에 열지 않는다(`127.0.0.1`에만 바인딩, 3단계에서 적용 완료). 프록시가 `Host`, `X-Forwarded-For`, `X-Forwarded-Proto`를 넘겨야 앱의 `forward-headers-strategy=framework`가 동작한다.
- [ ] certbot으로 인증서 발급·자동 갱신. Amazon Linux 2023 저장소에 certbot이 있어 `dnf install -y nginx certbot python3-certbot-nginx`로 설치하고(`certbot plugins`로 nginx 플러그인 확인), 갱신은 `certbot-renew.timer`를 켜서 처리한다. 순서: ① `--dry-run`(스테이징에서 시뮬레이션, 발급 한도를 쓰지 않음)으로 DNS·80 포트·설정 검증 → ② 실제 발급 → ③ `certbot renew --dry-run`으로 갱신 경로 검증.
  - 위험: Let's Encrypt는 "등록 도메인당 발급 횟수" 한도가 있는데, 공용 무료 도메인(`duckdns.org`)이 공개 접미사 목록(PSL)에 있는지 확인하지 못했다. 목록에 없으면 같은 도메인을 쓰는 다른 사용자와 한도를 나눠 발급이 거부될 수 있다. 그래서 반드시 `--dry-run`을 먼저 하고, 거부되면 정식 도메인 구매를 앞당긴다.
- [ ] (정식) 도메인 구매 후 같은 탄력적 IP로 A 레코드 연결 → Nginx `server_name`과 인증서에 새 도메인 추가 → 클라이언트 서버 주소 변경·배포. **전환 기간에는 두 도메인을 함께 서비스**하고, 구 클라이언트가 사라지면 임시 도메인을 제거한다.
- 검증: 내 PC에서 `curl https://도메인/actuator/health`가 성공한다. 클라이언트 접속 주소를 이 도메인으로 바꿔 게스트 로그인이 동작한다.
- 참고: JWT 발급자(`app.jwt.issuer`)는 URL이 아니라 고정 문자열이라 도메인을 바꿔도 발급된 토큰이 무효화되지 않는다. 무료 도메인은 가용성과 인증서 발급 한도를 보장하지 않으므로 정식 출시에는 쓰지 않는다.

### 5단계. CI 확장 (PR 단계)
- [ ] 새 워크플로 `ci.yml`: `./gradlew build`(테스트 포함) 실행. ubuntu 러너에는 Docker가 있어 Testcontainers가 그대로 동작한다.
- [ ] Gradle 캐시 적용으로 시간 단축
- [ ] GitHub 브랜치 보호: `main`에 Checkstyle·빌드(Test)·식별 정보 검사(Identifier check) 통과 필수, 리뷰 1명 이상(CONTRIBUTING에 이미 명시된 방침). 새 검사는 워크플로가 한 번 실행된 뒤에야 필수 검사 목록에 나타난다.
- 검증: 테스트가 깨지는 PR은 병합 버튼이 막힌다.

### 6단계. CD (main 병합 시 자동 배포)
- [ ] AWS에 GitHub OIDC 공급자와 배포용 IAM 역할 생성(저장소·브랜치로 신뢰 범위 제한, 권한은 최소)
- [ ] 워크플로 `deploy.yml`(`main` 푸시 트리거): 테스트 → 이미지 빌드(태그: 커밋 SHA) → ECR 푸시 → SSM Run Command로 EC2의 `deploy.sh <태그>` 실행 → 헬스체크 → 실패 시 자동으로 이전 태그로 롤백
  - 교체·헬스체크·롤백 로직은 `deploy/deploy.sh`에 있다. 워크플로는 이 스크립트를 SSM으로 EC2에서 실행하고 **종료 코드와 출력**만 본다. 스크립트가 EC2에 있어야 하므로 워크플로가 실행 직전에 저장소의 파일 내용을 SSM 명령으로 전달하는 방식을 쓴다.
- [x] 직전에 정상 동작한 태그를 EC2에 기록해 두어 롤백 대상으로 쓴다: `deploy.sh`가 `/var/lib/blackhole/last-good-tag`에 기록한다. 다만 ECR 수명 주기 규칙(최근 10개 유지)이 그 이미지를 지우지 않는지는 남은 확인 사항이며, 스크립트는 이미지가 로컬에 없으면 다시 받는다.
- [ ] 배포 동시 실행 방지(`concurrency`), 실패 시 알림(GitHub 기본 메일 또는 Slack)
- 검증: 사소한 변경을 `main`에 병합하면 사람 손 없이 배포되고, 일부러 헬스체크가 실패하는 변경을 넣으면 롤백된다.

### 7단계. 운영 최소 점검
- [ ] 로그 확인법 정리(`docker logs`, 로그 크기 제한 설정, 필요 시 CloudWatch Logs로 전송), 디스크 정리(`docker image prune`)
- [ ] CloudWatch 알람: EC2 CPU·상태 체크, RDS 저장공간·연결 수
- [ ] RDS 백업 복구 절차 한 번 시험(스냅샷에서 복원)
- [ ] 운영 런북 작성: 배포 / 롤백 / 비밀값 교체 / DB 접속 방법
- [ ] 비용 확인 주기 정하기, 사용하지 않을 때 중지할 리소스 목록

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
