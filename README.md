# Feed A Blackhole Server

백엔드 부트캠프 최종 프로젝트의 Spring Boot 서버 저장소입니다.
**A Game About Feeding A Black Hole**을 레퍼런스로 하는 인크리멘탈 게임을 개발합니다.
클라이언트는 별도 Unity 저장소에서 개발합니다.

현재 서버는 프로젝트 초기 골격과 협업 설정 단계입니다. 게임 API와 Unity 연동은 아직 구현되지 않았습니다.

## 개발 환경

- JDK **21** (`build.gradle`의 Java Toolchain 기준)
- Spring Boot **4.1.1**
- Gradle **9.7.1** (저장소의 Wrapper 사용, 별도 Gradle 설치 불필요)
- Spring MVC, Spring Data JPA, MySQL JDBC 드라이버, Lombok
- Git 및 Java 개발용 IDE (예: IntelliJ IDEA)

MySQL 서버 버전·로컬 DB 준비 방식·테스트 DB 구성은 아직 확정하지 않았습니다.
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

**현재는 DB 연결과 테스트 DB 설정이 없어 위 빌드·테스트·서버 실행의 성공을 보장하지 않습니다.**
JPA와 MySQL 드라이버가 포함되어 있으며, 기본 `@SpringBootTest`도 애플리케이션 컨텍스트를 시작합니다.
DB 구성 없이 실행하면 데이터 소스 초기화가 실패할 수 있습니다. 테스트를 제외해 통과시키는 대신 DB·테스트 환경을 후속 단계에서 구성합니다.

## 환경 설정과 다음 단계

- 공통 설정 파일은 `src/main/resources/application.properties`입니다. 현재는 애플리케이션 이름만 지정되어 있습니다.
- 인증 정보는 커밋하지 않습니다. `.env`와 `.env.*`는 제외하고 `.env.example`만 공유할 수 있습니다.
- Spring Boot는 `.env` 파일을 자동으로 읽지 않습니다. 실제 환경변수와 주입 방식은 DB 구성 시 함께 안내합니다.
- 다음 단계에서 로컬 MySQL 준비, DB 연결 설정, 테스트 DB와 GitHub Actions CI를 구성합니다.
- 코드 스타일 전용 GitHub Actions는 구성되어 있으며, 푸시 후 PR과 `main`에서 Checkstyle을 실행합니다. 위의 후속 CI는 전체 빌드·DB 테스트를 의미합니다.
- 브랜치 보호·필수 리뷰와 병합 방식은 GitHub에서 별도로 설정해야 합니다. 문서와 템플릿 추가만으로 활성화되지 않습니다.

협업 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 참고하세요.
