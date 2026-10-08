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

## DB 스키마 (Flyway)

- 스키마 변경은 `src/main/resources/db/migration/V<번호>__<설명>.sql` 파일로 추가합니다. 예: `V1__create_account.sql`
- 이미 적용·병합된 파일은 수정하지 않고, 변경이 필요하면 새 번호의 파일을 추가합니다. 수정하면 Flyway가 체크섬 불일치로 시작을 거부합니다.
- JPA는 `ddl-auto=validate`라서 엔티티와 스키마가 다르면 시작 단계에서 실패합니다.
- 테스트도 같은 마이그레이션을 적용한 MySQL 컨테이너에서 실행됩니다.

## 환경 설정과 다음 단계

- 공통 설정 파일은 `src/main/resources/application.properties`입니다.
- 인증 정보는 커밋하지 않습니다. `.env`와 `.env.*`는 제외하고 `.env.example`만 공유합니다.
- 전체 빌드·DB 테스트를 실행하는 GitHub Actions CI는 후속 단계입니다. 현재 CI는 코드 스타일(Checkstyle) 전용입니다.
- 브랜치 보호·필수 리뷰와 병합 방식은 GitHub에서 별도로 설정해야 합니다. 문서와 템플릿 추가만으로 활성화되지 않습니다.

## 노드 콘텐츠

노드 트리(노드, 비용, 효과, 배치)의 원본은 `src/main/resources/content/nodes/`의 파일이며, 서버가 시작할 때 읽어 검증합니다.
콘텐츠에 문제가 있으면 모든 문제를 알리고 서버가 시작되지 않습니다. 형식, 규칙, 수정 절차는 [docs/node-content.md](docs/node-content.md)를 참고하세요.

협업 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 참고하세요.
