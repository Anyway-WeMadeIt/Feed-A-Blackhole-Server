# Feed A Blackhole Server

백엔드 부트캠프 최종 프로젝트의 Spring Boot 서버 저장소입니다.
**A Game About Feeding A Black Hole**을 레퍼런스로 하는 인크리멘탈 게임을 개발합니다.
클라이언트는 별도 Unity 저장소에서 개발합니다.

현재 서버는 프로젝트 초기 골격과 협업 설정 단계입니다. 게임 API와 Unity 연동은 아직 구현되지 않았습니다.

## 개발 환경

- JDK **21** (`build.gradle`의 Java Toolchain 기준)
- Spring Boot **4.1.1**
- Gradle **9.7.1** (저장소의 Wrapper 사용, 별도 Gradle 설치 불필요)
- Spring MVC, Spring Data JPA, MySQL JDBC 드라이버, Flyway, Lombok
- MySQL **8.4** (로컬 개발은 Docker, 테스트는 Testcontainers)
- Docker (Docker Desktop 등). 로컬 DB와 테스트에 필요합니다.
- Git 및 Java 개발용 IDE (예: IntelliJ IDEA)

버전의 실제 기준은 `build.gradle`과 `gradle/wrapper/gradle-wrapper.properties`입니다.

## 시작하기

JDK 21과 Git을 설치한 후 저장소를 복제합니다.

```sh
git clone https://github.com/Anyway-WeMadeIt/Feed-A-Blackhole-Server.git
cd Feed-A-Blackhole-Server
git config --local commit.template .gitmessage
```

1. `JAVA_HOME`이 JDK 21을 가리키도록 설정합니다.
2. IDE에서 저장소 루트를 Gradle 프로젝트로 엽니다.
3. 프로젝트 SDK와 Gradle JVM을 JDK 21로 지정하고 의존성 동기화를 기다립니다.
4. 아래 명령으로 Wrapper 및 Java 환경을 확인합니다. 최초 실행에는 Gradle 다운로드를 위한 네트워크 연결이 필요합니다.

Windows PowerShell:

```powershell
java -version
.\gradlew.bat --version
```

macOS/Linux:

```sh
java -version
bash ./gradlew --version
```

커밋 템플릿 설정은 복제본마다 한 번 적용해야 합니다. `git commit` 실행 시 템플릿이 열리며,
`git commit -m` 또는 일부 IDE의 커밋 입력 창에는 자동 적용되지 않습니다.

## 빌드·테스트·실행

| 작업 | Windows PowerShell | macOS/Linux |
| --- | --- | --- |
| 빌드 및 테스트 | `.\gradlew.bat build` | `bash ./gradlew build` |
| 테스트 | `.\gradlew.bat test` | `bash ./gradlew test` |
| 컨벤션 검사 (DB 불필요) | `.\gradlew.bat checkstyleMain checkstyleTest` | `bash ./gradlew checkstyleMain checkstyleTest` |
| 서버 실행 | `.\gradlew.bat bootRun` | `bash ./gradlew bootRun` |

`test`와 `build`는 Testcontainers가 MySQL 컨테이너를 자동으로 띄우므로 **Docker가 실행 중이어야 합니다.**
`bootRun`은 아래 로컬 DB가 떠 있어야 합니다.

### 테스트 구성

Unity 클라이언트 없이 서버만으로 검증합니다. 모든 테스트는 실제 MySQL 8.4 컨테이너에서 실행됩니다.

| 종류 | 위치 | 확인하는 것 |
| --- | --- | --- |
| 스키마·매핑 | `*SchemaTests`, `AuditingTests` | Flyway 스키마와 엔티티·제약 조건의 일치 |
| 단위 | `SecretCodecTests` | 비밀값 생성·해시 |
| API(서버 내부 호출) | `*ApiTests` | 컨트롤러·보안·검증·서비스·DB를 `MockMvc`로. 테스트마다 롤백 |
| **클라이언트 흐름(실제 HTTP)** | `e2e/Stage1ClientFlowTests` | 서버를 실제 포트로 띄우고 `support/FakeClient`가 명세(`docs/api-spec.md`)대로 요청을 조립해 전송. 최초 실행, 재시작, 토큰 만료(시계를 앞당김), 기기 두 대, 오류 응답 형식, **실제 동시 요청**(토큰 갱신) |
| **동기화 흐름(실제 HTTP)** | `e2e/SyncClientFlowTests` | 서버를 실제 포트로 띄우고 `FakeClient`가 샘플 로그(`support/SyncSamples`)를 JSON 글자 그대로 `POST /me/sync`에 보낸다. 순차 적용, 나눠 보내기, 재전송, 누락, 거부 후 멈춤, 의심 표시 후 계속, 중복 `runId`, **실제 동시 요청**(두 기기), 토큰 만료, 콘텐츠 버전 불일치, 빈 요청 |

- `FakeClient`는 명세의 클라이언트 처리 흐름을 구현한 기준 구현입니다. 서버의 DTO를 쓰지 않고 JSON 문자열을 직접 조립하므로, 서버와 명세가 함께 틀어지는 일을 잡습니다.
- 새 API를 만들면 FakeClient로 시나리오를 추가하고(인증·진행도 조회는 `Stage1ClientFlowTests`, 동기화는 `SyncClientFlowTests`), 명세를 먼저 고친 뒤 구현합니다.

## 로컬 DB 준비

```sh
cp .env.example .env        # Windows PowerShell: Copy-Item .env.example .env
docker compose up -d        # MySQL 8.4 시작 (healthy가 될 때까지 몇 초 걸립니다)
```

- `.env`의 값(DB 이름·계정·비밀번호·포트)을 `compose.yaml`과 Spring Boot가 함께 읽습니다. `.env`는 커밋하지 않으며, 비밀번호는 각자 바꿔도 됩니다.
- 서버 실행: `.\gradlew.bat bootRun` (포트 8080). 시작할 때 Flyway가 DB 스키마를 최신으로 맞춥니다.
- 중지: `docker compose stop`. 데이터까지 지우려면 `docker compose down -v`.
- JWT 서명 키 `JWT_SECRET`도 `.env`에 둡니다. 32자 이상의 무작위 문자열이어야 하며, 비어 있거나 짧으면 서버가 시작되지 않습니다. 생성 예: `python -c "import secrets;print(secrets.token_urlsafe(48))"`. 키를 바꾸면 이미 발급된 액세스 토큰이 모두 무효가 됩니다.
- 배포 환경에서는 `.env` 대신 환경변수 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`으로 주입합니다.

## 컨테이너 이미지

운영 환경은 Docker 이미지로 실행합니다. 이미지에는 `.env`와 비밀값이 들어가지 않으며(`.dockerignore`), 설정은 실행할 때 환경변수로 넣습니다.

```sh
docker build -t feedablackhole-server .
docker run --rm -p 8080:8080 \
  -e DB_URL='jdbc:mysql://host.docker.internal:3306/feedablackhole' \
  -e DB_USERNAME=blackhole -e DB_PASSWORD=<비밀번호> -e JWT_SECRET=<32자 이상 무작위 문자열> \
  feedablackhole-server
```

- 로컬 MySQL(`docker compose up -d`)에 붙여 보려면 위처럼 `DB_URL`로 호스트의 DB를 가리킵니다. 비밀번호는 `.env` 값을 사용합니다.
- 헬스체크: `GET /actuator/health`는 토큰 없이 호출할 수 있고, DB에 연결되지 않으면 `503 {"status":"DOWN"}`을 반환합니다. 상세 정보는 노출하지 않으며 다른 actuator 경로는 열려 있지 않습니다.
- 종료 신호(SIGTERM)를 받으면 처리 중인 요청을 마친 뒤(최대 20초) 내려갑니다.

## DB 스키마 (Flyway)

- 스키마 변경은 `src/main/resources/db/migration/V<번호>__<설명>.sql` 파일로 추가합니다. 예: `V1__create_account.sql`
- 이미 적용·병합된 파일은 수정하지 않고, 변경이 필요하면 새 번호의 파일을 추가합니다. 수정하면 Flyway가 체크섬 불일치로 시작을 거부합니다.
- JPA는 `ddl-auto=validate`라서 엔티티의 테이블·컬럼이 없거나 타입이 다르면, 그리고 유니크 제약·인덱스의 이름이나 컬럼 구성이 SQL과 다르면 시작 단계에서 실패합니다. (유니크·인덱스 검증은 `application.properties`의 `hibernate.tooling.schema.*_validation=ALL` 설정으로 켜 두었습니다.)
- 컬럼 길이, `NOT NULL`, 외래키는 검증되지 않습니다. SQL을 바꾸면 엔티티도 직접 같이 고쳐 주세요.
- 테스트도 같은 마이그레이션을 적용한 MySQL 컨테이너에서 실행됩니다.

## 환경 설정과 다음 단계

- 공통 설정 파일은 `src/main/resources/application.properties`입니다.
- 인증 정보는 커밋하지 않습니다. `.env`와 `.env.*`는 제외하고 `.env.example`만 공유합니다.
- GitHub Actions CI는 세 워크플로입니다. **Code style**(`.github/workflows/code-style.yml`)은 Checkstyle만 실행하고, **Tests**(`.github/workflows/test.yml`)는 `./gradlew test` 전체(Testcontainers MySQL, E2E 포함)를 실행합니다. 셋째인 **Identifier check**(`.github/workflows/identifier-check.yml`)는 문서·설정에 계정 ID, 엔드포인트, 리소스 ID, 공인 IP 같은 인프라 식별 정보가 들어갔는지 `scripts/check-identifiers.sh`로 검사합니다([CONTRIBUTING.md](CONTRIBUTING.md)의 "인프라 식별 정보" 참고). 셋 모두 PR과 `main` 푸시에서 돌고 서로 별개의 검사(`Checkstyle`, `Test`, `Identifier check`)로 표시됩니다. Tests는 `.env`나 시크릿이 필요 없고(`build.gradle`이 테스트용 값을 지정), 실패하면 `build/reports/tests/`와 `build/test-results/`를 `test-reports` 아티팩트로 올립니다.
- 브랜치 보호·필수 리뷰와 병합 방식은 GitHub에서 별도로 설정해야 합니다. 문서와 템플릿 추가만으로 활성화되지 않습니다.

## 배포와 운영

운영 환경은 AWS 서울 리전입니다.

```
클라이언트 ─HTTPS─▶ Nginx(EC2) ─▶ 앱 컨테이너(127.0.0.1:8080) ─▶ RDS(MySQL 8.4, 프라이빗 서브넷)
```

- 앱은 EC2에서 Docker 컨테이너로 실행하고, 이미지는 ECR에 올립니다. 서버 접속은 SSH 없이 SSM Session Manager로 합니다.
- 설정과 비밀값(`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`)은 이미지에 넣지 않고 Parameter Store(`/blackhole/*`)에서 읽어 실행할 때 환경변수로 주입합니다.
- 이미지 교체와 롤백은 EC2에서 [`deploy/deploy.sh <태그>`](deploy/deploy.sh)로 합니다. 설정을 검증한 뒤 새 이미지를 받아 컨테이너를 교체하고, 헬스체크(`/actuator/health`)가 실패하면 직전 정상 태그로 되돌립니다.
- HTTPS 프록시(Nginx) 설정과 인증서 발급·갱신 절차는 [deploy/nginx/README.md](deploy/nginx/README.md)에 있습니다.
- 현재는 사람이 `deploy.sh`를 실행합니다. `main` 병합 시 자동 배포(CD)는 후속 단계입니다.
- 전체 계획과 진행 상황은 [docs/deploy-plan.md](docs/deploy-plan.md)를 참고하세요.
- 이 저장소는 공개라서 계정 ID, 엔드포인트, IP 같은 배포 환경의 식별 정보는 문서에 적지 않습니다([CONTRIBUTING.md](CONTRIBUTING.md)).

## 노드 콘텐츠

게임 콘텐츠(노드 트리, 전투 데이터)의 원본은 **클라이언트 저장소**입니다. `src/main/resources/content/`의 파일은 클라이언트의 내보내기 도구(`BlackHole > Server Content > Export...`)가 만든 사본이며, 이 저장소에서 손으로 고치지 않습니다.
서버는 시작할 때 이 파일들을 읽어 검증하고, 문제가 있으면 모든 문제를 알리고 시작하지 않습니다. 형식과 갱신 절차는 [docs/node-content.md](docs/node-content.md)와 [docs/server-sync-contracts.md](docs/server-sync-contracts.md) 1장을 참고하세요.

협업 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 참고하세요.
