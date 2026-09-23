# 협업 규칙

## 브랜치와 PR

- `main`을 기준으로 작업 브랜치를 만들고 PR로 병합합니다.
- 브랜치는 `feat/주제`, `fix/주제`, `chore/주제`처럼 목적을 드러내는 이름을 사용합니다.
- PR은 하나의 목적에 집중하고, 최소 1명의 팀원 리뷰 후 병합하는 것을 기본으로 합니다.
- PR 템플릿에 변경 이유, 검증 결과와 관련 이슈를 작성합니다.
- API 계약을 변경하면 요청·응답, HTTP 상태 코드, 오류 처리와 Unity 측 수정 필요 여부를 설명합니다.
- DB 스키마·데이터 또는 환경 설정을 변경하면 적용 방법과 기존 환경에 미치는 영향을 설명합니다.
- 병합은 PR 단위로 기록을 남기는 Squash merge를 권장하며, 최종 커밋 제목에도 아래 형식을 사용합니다.

Java 컨벤션은 Gradle과 `.github/workflows/code-style.yml`에서 검사합니다. GitHub에 푸시하면 PR과 `main` 푸시에서 실행됩니다.
브랜치 보호와 필수 리뷰는 별도 설정이 필요합니다. 최초 실행 후 GitHub의 `main` 보호 규칙에서 **Checkstyle** 검사를 필수로 지정하고 승인 1명 이상을 요구하면, 검사 실패나 리뷰 누락 시 병합을 차단할 수 있습니다. 이 저장소 변경만으로 원격 보호 규칙이 활성화되지는 않습니다.

## 커밋

```text
type(scope): 설명
```

`scope`는 선택이며, 제목과 본문은 한국어로 작성할 수 있습니다. 클라이언트 저장소와 같은 타입을 사용합니다.

| type | 용도 |
| --- | --- |
| `feat` | 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 동작을 유지하는 코드 구조 개선 |
| `docs` | 문서 변경 |
| `test` | 테스트 추가·수정 |
| `chore` | 설정, 패키지 및 기타 유지보수 |

예: `feat(upgrade): 업그레이드 구매 API 추가`

저장소 루트에서 `git config --local commit.template .gitmessage`를 실행하면 메시지 편집기를 사용하는 `git commit`에 템플릿이 적용됩니다.
복제본마다 한 번 설정해야 하며, `git commit -m` 또는 일부 IDE의 커밋 입력 창에는 자동 적용되지 않습니다.
기본 Git 설정에서 템플릿의 `#` 주석은 최종 커밋 메시지에서 제외됩니다. 커밋 메시지 형식 검사 훅은 사용하지 않습니다.

## Java 컨벤션

- `.editorconfig`를 기준으로 UTF-8, LF, 공백 4칸을 사용합니다. JSON·YAML은 공백 2칸입니다.
- 클래스·인터페이스·enum·record 이름은 `PascalCase`를 사용합니다. 인터페이스에 C#의 `I` 접두사를 붙이지 않습니다.
- 메서드·필드·매개변수·지역 변수는 `camelCase`를 사용하며, private 필드에 `_` 접두사를 붙이지 않습니다.
- 상수와 enum 상수는 `UPPER_SNAKE_CASE`를 사용합니다. `final` 지역 변수나 인스턴스 필드는 일반 변수 규칙을 따릅니다.
- 패키지는 소문자를 사용합니다. public 최상위 타입과 파일 이름을 일치시킵니다.
- 제네릭 타입 매개변수는 `T`, `E`, `K`, `V`처럼 역할에 맞게 지정합니다.
- 여는 중괄호는 선언·조건문과 같은 줄에 두며, 한 줄 조건문에도 중괄호를 사용합니다.
- 들여쓰기를 한 단계 더 하는 연속 줄은 공백 8칸을 기준으로 합니다. 읽기 어려운 긴 표현식은 분리합니다.
- 와일드카드 import와 사용하지 않는 import를 피합니다.
- 변경과 무관한 파일 전체 포맷팅은 피합니다.

```java
public class UpgradeService {
    private static final int MAX_LEVEL = 10;

    public boolean canUpgrade(int currentLevel) {
        return currentLevel < MAX_LEVEL;
    }
}
```

`.editorconfig`에는 기본 공백·인코딩 규칙과 IntelliJ IDEA의 Java 전용 `ij_*` 설정을 함께 관리합니다.
IntelliJ IDEA에서 EditorConfig 지원을 활성화한 뒤 **Reformat Code**를 실행하면 중괄호 위치, 조건문·반복문의 중괄호 추가, 연속 줄 들여쓰기 등이 적용됩니다.
**Optimize Imports**를 실행하면 사용하지 않는 import를 제거하고 개별 import를 사용하도록 정리합니다.
일반·static import의 와일드카드 전환 기준은 9999개로 높이고 기본 와일드카드 패키지 목록을 비웠습니다. 이는 자동 변환 억제 설정이며 와일드카드 사용을 오류로 검사하는 규칙은 아닙니다.

`ij_*`는 IntelliJ 확장 속성이므로 다른 IDE에서는 무시될 수 있습니다. 설정 추가만으로 기존 코드가 일괄 수정되거나 저장 시 포맷팅이 활성화되지는 않습니다.
필요하면 IDE의 Actions on Save에서 Reformat code와 Optimize imports를 별도로 활성화합니다.
Java 명명 규칙은 EditorConfig가 아닌 **Checkstyle**로 검사합니다. 공통 검사 설정은 `config/checkstyle/checkstyle.xml`, 도구 버전은 `build.gradle`의 `toolVersion`을 기준으로 합니다.

설정 참고: [IntelliJ IDEA EditorConfig](https://www.jetbrains.com/help/idea/editorconfig.html), [Java Code Style](https://www.jetbrains.com/help/idea/code-style-java.html).

## 자동 컨벤션 검사

Windows에서는 아래 명령을 실행합니다. macOS/Linux에서는 `bash ./gradlew checkstyleMain checkstyleTest`를 사용합니다.

```powershell
.\gradlew.bat checkstyleMain checkstyleTest
```

- 운영 코드와 테스트 코드에 같은 규칙을 적용하고, 위반 시 명령이 실패합니다. DB 연결이나 서버 실행은 필요하지 않습니다.
- 타입·메서드·필드·상수·패키지·변수·record 컴포넌트 등의 이름, 파일명과 최상위 타입 일치, 필수 중괄호와 여는 중괄호 위치, 와일드카드·미사용 import를 검사합니다.
- 제네릭 타입 매개변수는 대문자 한 글자로 통일합니다. 상수 검사는 `static final` 필드와 인터페이스 상수 등에 적용되며 Java 직렬화 표준 이름에는 도구의 예외가 적용됩니다.
- 이름의 업무 의미나 인터페이스 `I` 접두사 여부처럼 단순 대소문자 패턴만으로 판단하기 어려운 규칙은 리뷰로 확인합니다.
- `check`와 `build`에도 검사가 자동 연결됩니다. 이 명령들은 테스트까지 포함하므로 DB 환경 준비가 별도로 필요합니다.
- HTML 보고서는 `build/reports/checkstyle/main.html`, `test.html`에 생성됩니다.
- IntelliJ에서 Checkstyle 연동 플러그인을 사용하는 경우 동일한 XML과 도구 버전을 지정합니다. IDE 플러그인 없이도 Gradle 검사는 동작하며, CI 결과를 공통 기준으로 삼습니다.
- 검사기는 코드를 자동 수정하지 않습니다. 포맷은 Reformat Code, import는 Optimize Imports, 이름은 IDE Rename 리팩터링으로 수정합니다.

현재 CI는 코드 스타일 전용입니다. 전체 빌드·DB 통합 테스트 CI는 후속 단계이며, 로컬 Git 훅과 별도 빌드 포매터는 추가하지 않았습니다.

## Spring Boot·Gradle과 Git

- JDK 21을 사용하고 IDE의 프로젝트 SDK와 Gradle JVM도 21로 맞춥니다.
- 시스템에 설치한 `gradle` 대신 저장소의 `gradlew` 또는 `gradlew.bat`를 사용합니다.
- `build.gradle`, `settings.gradle`, `gradlew`, `gradlew.bat`, `gradle/wrapper` 전체를 함께 관리합니다. Wrapper JAR도 커밋 대상입니다.
- Java·Spring Boot·Gradle 버전과 의존성 변경은 PR에서 팀과 공유합니다.
- `.gradle`, `build`, IDE 개인 설정과 OS 생성 파일은 커밋하지 않습니다. 서버 저장소에서는 `.vscode` 전체를 제외합니다.
- 공통 애플리케이션 설정은 커밋하되 비밀번호·토큰·개인 접속 정보는 넣지 않습니다. `.env.example`에는 예시 값만 기록합니다.
- Spring Boot는 `.env`를 기본으로 로드하지 않습니다. 환경변수 주입 방법과 DB 설정은 실행 환경 구성 단계에서 문서화합니다.
- `.gitattributes`로 텍스트는 LF, Windows 배치 파일은 CRLF를 사용합니다. 서버에는 Git LFS나 Unity 병합 드라이버를 설정하지 않습니다.

## 제출 전 확인

1. 코드 변경 시 Wrapper로 `build`를 실행해 컴파일·테스트를 확인합니다. 현재 DB 구성의 제한은 [README.md](README.md)를 참고합니다.
   DB 준비 전에도 `checkstyleMain checkstyleTest`는 실행해 컨벤션을 확인합니다.
2. API 변경은 정상·오류 요청을 확인하고 요청·응답 예시와 결과를 PR에 기록합니다.
3. DB 변경은 기존 데이터 영향과 적용 순서를 확인합니다.
4. `git diff --check`와 `git status --short`로 공백 오류와 불필요한 파일을 확인합니다.
5. 문서·설정만 변경했다면 관련 규칙과 링크를 확인합니다. 검증하지 못한 항목은 PR에 이유를 명시합니다.
