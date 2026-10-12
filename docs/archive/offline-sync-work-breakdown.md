# 작업 방향과 작업 정의

- 상태: **작업 정의 최종본** (범위 확정, 2026-10-12). 방향과 배경은 [offline-sync-direction.md](offline-sync-direction.md), 계약은 [api-spec.md](../api-spec.md)에 있다.
- 제외한 범위와 그 설계는 **[offline-sync-design-archive.md](offline-sync-design-archive.md)** 에 보존했다. 이 문서에는 구현할 것만 적는다.
- A1, A2, A4'의 세부 계약(파일 형식, 서버 객체, 검증 규칙, 번들 해시)은 **[battle-content-contract.md](battle-content-contract.md)** 에 있다. L1 검증이 서버에 요구하는 정보는 **[l1-verification-requirements.md](l1-verification-requirements.md)** 에 정리했다.
- 제약: 작업 기간 **10일 이내**, 후속 단계가 없으므로 **제외는 곧 구현하지 않는다**는 뜻이다. 인원별 배분과 일정은 이 문서에 두지 않는다(작업과 선행 관계만 정의한다).
- 규모는 인일 기준 추정이다(S 약 1일, M 2~3일, L 4~5일). 영역: **C**(클라이언트, Unity) / **S**(서버, Spring Boot) / **C+S**.

## 1. 목표와 범위

**목표:** 오프라인으로 플레이한 구매와 전투가 재접속 때 서버에 제출되어, 서버가 **규칙과 개연성으로 검증한 결과만 정본에 반영**된다. 전투 검증은 **L1**(결산 요약, 장부, 공급 상한)까지다.

| 구현한다 | 구현하지 않는다 (archive에 설계 보존) |
|---|---|
| 동기화 API(`POST /me/sync`)와 서버 적용 골격 | 이벤트 단위 전투 검증 L3 (D1~D6) |
| 노드 구매 검증(정확), `reset` | 적 구성 규칙의 완전 이식(색 분포와 성질 선택의 정확 재현) |
| 전투 L1 검증: 시작 스냅샷, 장부, 결산 규칙, **공급 상한**, 시간 상한 | 단일 기기 로그인, Unity 에디터 도구, 서버 JSON 정본 방향, CI 일치 검사 |
| 전투 콘텐츠의 서버 이관(Unity → JSON 내보내기)과 번들 해시 | gzip, 분할 전송, 콘텐츠 이력, `/system/status`, 429 |
| **클라이언트 API 계층과 인증**, 로그 기록기, 동기화 클라이언트 | 랭킹, 도전과제 (조건부, 아래 E) |
| 옛 API(`PUT /me/progress`, `POST /me/progress/reset`) **제거** | |

**완성된 제품의 한계** (문서에 명시한다)

- 공급 상한 **안에서의** 처치 속도 조작은 막지 못한다(이벤트가 없어 버프 의존 피해, Tick 간격, 타격 대상 수를 보지 못한다).
- 클라이언트가 유리한 시드를 고르는 것은 막지 못한다.
- 같은 계정이 두 기기에서 갈라져 진행하면 먼저 도착한 쪽이 채택되고 나머지는 서버 상태로 맞춰진다.
- 노드 수치를 바꾸면 양쪽 사본을 같이 고쳐야 한다(수동).

## 2. 확정된 결정

1. 서버는 모든 계정의 진행도 **정본**을 가지고, 클라이언트는 로컬 진행도와 **행위 로그**를 가진다. 서버는 로그를 순서대로 적용하며 검증하고, 서버가 계산한 상태가 항상 이긴다.
2. 전투 검증은 **L1까지**다. L1은 "클라이언트가 주장한 값을 정산에 쓰지 않고 서버가 장부로 다시 계산해 대조"하는 방식이다.
3. **데이터 정본은 Unity 에셋** 이다. 전투 콘텐츠는 Unity에서 편집하고 **JSON으로 내보내 서버 저장소에 둔다.** 노드 CSV는 서버 파일이 현재 정본이므로 클라이언트에 같은 내용을 두고, 이후 수정은 클라이언트에서 편집한 뒤 `../../tools/import_node_content.py`로 서버에 내보낸다. **같은 정보가 양쪽에 있고 일치하는 것**이 핵심이다.
4. 콘텐츠 일치는 **번들 해시**로 확인한다. 전투 콘텐츠 JSON 파일의 SHA-256(앞 16자리)을 서버는 **파일 바이트로**, 클라이언트는 **에디터가 빌드 시점에 계산해 빌드에 포함**한다(런타임에 직렬화해서 계산하면 float 표기가 플랫폼마다 달라져 해시가 어긋날 수 있다). 다르면 `CONTENT_VERSION_MISMATCH`로 거절한다.
5. 무효 로그는 **첫 무효 직전까지 적용하고 이후 폐기**한다(`PARTIAL`). 기준이 다르면 아무것도 적용하지 않고 서버 상태를 채택한다(`BASE_MISMATCH`). 거절은 모두 영구이고 재시도 구분은 없다.
6. 구매, 전투, 새 게임은 모두 `POST /me/sync`의 엔트리다. 온라인이면 한 건씩 즉시, 오프라인이면 쌓아 두었다가 제출한다.
7. 위치와 관련된 것은 검증하지 않는다.
8. **모바일이 주 대상 플랫폼**이다. 배속과 슬로우 모션은 없다.
9. 운영 규칙: 콘텐츠를 바꾸면 **내보내기 → 서버 저장소 커밋 → 서버 배포 → 클라이언트 배포** 순서를 지킨다(서버를 먼저 배포). 이번 기간에는 **노드 수치를 수정하지 않는다.**

## 3. 착수 전에 맞출 접점 (계약)

작업들이 서로를 기다리지 않으려면 아래 계약을 먼저 합의한다. 각 계약은 해당 작업 정의에 세부가 있다.

| # | 계약 | 맞추는 작업 | 세부 |
|---|---|---|---|
| 1 | **서버 엔트리 핸들러 인터페이스**와 작업 상태 객체 | B2, B3, C3 | B2 |
| 2 | **콘텐츠 JSON의 스키마, 직렬화 규칙, 파일 위치**(최상위 `{FormatVersion, Content}` 래퍼, enum은 이름) | A1, A2 | [계약 문서](battle-content-contract.md) 2, 4장 |
| 3 | **번들 해시 알고리즘**(BOM 제거, CRLF를 LF로, SHA-256 앞 16자리)과 **공유 테스트 벡터** | A1, A2, A4' | [계약 문서](battle-content-contract.md) 3장 |
| 4 | **엔트리 필드**(`kills` 형식 포함) | B4, C1, B2, C3 | api-spec |
| 5 | **기대값 fixture 형식** | C0, C2, C4a, C4b, C3 | C0 |
| 6 | **클라이언트 가짜 서버** (명세를 구현한 인메모리 서버) | B5a, B5 | B5a |

## 4. 작업 정의

각 작업은 목적, 입력과 출력, 요구사항, 완료 조건으로 적는다. 코드 위치는 2026-10-12 시점이다.

### A. 콘텐츠

| ID | 작업 | 영역 | 규모 | 선행 |
|---|---|---|---|---|
| A1 | 전투 콘텐츠 내보내기 | C | 1.5 | |
| A2 | 서버 전투 콘텐츠 로더와 검증기, 번들 해시 | S | 3 | A1(파일) |
| A4' | 클라이언트 번들 해시(빌드 시점 계산) | C | 1 | A1 |
| A7' | 노드 CSV 일치 확인과 편집 절차 | C | 0.5 | |

#### A1. 전투 콘텐츠 내보내기

- **목적:** 서버가 전투 검증에 쓸 콘텐츠를 Unity 에셋에서 JSON으로 만든다.
- **입력:** `GameContentSetup.ToData()`가 만드는 `ContentData`(`Assets/Scripts/Core/Battle/Data/ContentData.cs`): `BattleRules`, `Breaker`, `Enemies`(적 종류, Tier, 성질과 사망 효과 수치, 시작 공급, 출현 띠, 주기 출현 띠), `Growth`(`LevelExp`, `Milestones`). 표현 데이터(색, 스프라이트)는 DTO에 없다.
- **출력:** 서버 저장소의 `src/main/resources/content/battle/content.json`. 서버가 읽는 필드와 읽지 않는 필드(이동 속도, 반지름, 브레이커, 출현 띠 등)는 [계약 문서](battle-content-contract.md) 4.2절의 표를 따른다(읽지 않는 필드도 **해시에는 들어간다**).
- **요구사항**
  1. Unity 메뉴 `BlackHole > Export Battle Content`. 서버 저장소의 로컬 체크아웃 경로는 Editor 사용자 설정(EditorPrefs)에 두고, 설정이 없으면 폴더를 고르게 한다.
  2. 내보내기 전에 `ContentLoader.Load`로 검증하고, **오류가 있으면 파일을 쓰지 않고** 진단을 콘솔에 보여 준다.
  3. **Newtonsoft를 `Packages/manifest.json`에 명시적으로 추가하고 버전을 고정한다**(`com.unity.nuget.newtonsoft-json` 3.2.2). 현재는 `com.unity.visualscripting`의 전이 의존으로만 설치되어 있어 다른 패키지가 빠지면 사라질 수 있고, 직렬화 결과가 해시에 들어가므로 버전이 바뀌면 안 된다.
  4. **직렬화(`ContentJson`)는 에디터 어셈블리**(`Assets/Scripts/Editor/`)에 한 곳으로 둔다(런타임은 직렬화하지 않으므로 `Core` 어셈블리를 건드리지 않는다). 내보내기와 A4'의 해시 계산이 같은 코드를 쓰고, 복사하지 않는다. 형식은 계약 문서를 따른다: 최상위 **`{ "FormatVersion": 1, "Content": … }` 래퍼**(`Content`는 `ContentData`와 필드 이름과 중첩이 같다), **enum은 이름(문자열)**(`JsonUtility`는 정수로 내보내 서버가 선언 순서에 의존하게 된다), DTO 선언 순서, 들여쓰기 2칸, **LF**, 끝에 개행, UTF-8 BOM 없음, `InvariantCulture`. 목록 순서(`Enemies.Enemies`, `StartSupply`)는 의미가 있으므로 바꾸지 않는다. `LevelExp`는 숫자 배열이다(현재 에셋은 16진수 덩어리로 저장되어 읽을 수 없다).
  5. 같은 내용이면 파일을 다시 쓰지 않는다(수정 시각과 diff 노이즈 방지).
  6. 끝나면 번들 해시와 파일 경로를 콘솔에 출력한다.
  7. 클라이언트 README에 콘텐츠를 바꾼 뒤의 절차(내보내기 → 서버 저장소 커밋 → 서버 배포 → 클라이언트 배포)를 적는다.
- **완료 조건(왕복 테스트, 에디터 EditMode):** `ContentData → Serialize → Deserialize`가 원본과 필드 단위로 같다(목록 순서 포함). 연속 두 번 직렬화한 결과가 바이트까지 같다. 내보낸 실제 파일이 `ContentLoader`를 통과한다. **공유 테스트 벡터**(계약 문서 3장: `{\n  "FormatVersion": 1\n}\n` → `a4ed84f2186aeaca`, CRLF와 BOM 입력도 같은 값)가 맞는다. 서버 저장소에 JSON이 있다.

#### A2. 서버 전투 콘텐츠 로더와 검증기

- **목적:** 서버가 시작할 때 전투 콘텐츠를 읽어 검증하고, 전투 검증 규칙(C2~C4, C3)이 쓸 불변 객체와 번들 해시를 제공한다.
- **입력:** `content.json`. **출력:** 불변 객체 `BattleContent`와 번들 버전 문자열.
- **요구사항**
  1. **패키지와 구조:** `org.example.feedablackhole.battle.content`에 Spring 비의존 순수 로직(읽기, 검증, 객체)을 두고, 시작 때 불러오는 연결은 `battle.config.BattleContentConfig`에 둔다. 노드 콘텐츠(`node.content`, `NodeContentConfig`)와 같은 구조를 따른다.
  2. **읽기:** Jackson으로 읽는다(`FAIL_ON_UNKNOWN_PROPERTIES = false`, `USE_BIG_DECIMAL_FOR_FLOATS = true`). 알 수 없는 필드는 무시한다. **enum은 이름이 정확히 같을 때만** 매핑하고(`Asteroid`, `Golden` 등, 대소문자 구분), 모르는 이름은 진단이다. 소수는 `BigDecimal`로 읽고 계산한다(노드 콘텐츠와 같은 원칙). Gold, EXP, `LevelExp`, `TargetGold`는 `long`이다. 최상위 `FormatVersion`이 1이 아니면 시작하지 않는다. **파일을 바이트로 읽어 해시를 계산한 뒤 같은 바이트를 파싱한다.**
  3. **객체 모델**(불변 객체, 목록은 수정 불가. 시그니처와 의미는 [계약 문서](battle-content-contract.md) 6.2절): `BattleContent`(`version()`, `rules()`, `kinds()`, `kind(type)`, `startSupply()`, `growth()`), `EnemyKind`(`type`, `tiers`, `traits`, `upgradesTo`, `spawnPeriod`, `rainCount`, `isPickup()`, `tier(index)`, `golden()`), `Tier(maxHealth, gold, exp)`, `GoldenEffect(multiplier, critChance, critRewardScale)`, `Growth`(`levelExp`, `milestones`, `startLevelAt`, `goalLevelAt`, `nextMilestoneAt`, `expToReach`, **`levelFor(exp, stage)`**: 목표 Level에서 멈추는 Level 계산), `Milestone(level, targetGold)`의 `rewardFor(gold)`. 서버가 읽지 않는 필드(`MoveSpeed`, `Radius`, `RadiusStep`, 황금 외 사망 효과, `Breaker`, 출현 띠, `FieldScale`)는 객체에 담지 않는다. 적 종류와 성질 enum의 JSON 이름은 C# enum 이름 그대로이고, `battle` 엔트리의 `kind`도 같은 이름을 쓴다(`EnemyType.fromJsonName`).
  4. **검증 규칙**(계약 문서 6.3절의 표). 진단은 **경로와 함께 모두 모아** 보고하고 하나라도 있으면 서버를 시작하지 않는다. **두 단계**로 본다(1단계 개별 값과 모양, 2단계 정의끼리의 규칙. 앞 단계에 오류가 있으면 뒤 단계는 보지 않는다). 주요 규칙: 적 종류 유일, 공급되는 종류는 Tier 6개, `MaxHealth > 0`, `Gold ≥ 0`, `Exp ≥ 0`, 변환 대상이 목록에 있고 픽업이 아니며 사슬이 돌지 않음, 시작 공급은 목록에 있고 픽업이 아니며 수량 1 이상, 성질 종류 중복 없음과 픽업은 성질이 정확히 하나, `LevelExp`는 양수이고 순증가, 이정표의 `Level`은 `LevelExp` 범위 안에서 순증가하고 `TargetGold`도 순증가, `TimeLimit > 0`, `KillTimeBonus ≥ 0`, 황금의 `Multiplier > 0`, `CritChance`는 0~1, `CritRewardScale > 0`. 서버가 읽지 않는 필드의 규칙(`Breaker`, 출현 띠 등)은 보지 않는다(클라이언트가 내보내기 전에 `ContentLoader`로 검증했다).
  5. **번들 해시:** 파일 바이트에서 **UTF-8 BOM을 제거하고** `\r\n`을 `\n`으로 바꾼 뒤 SHA-256을 구해 소문자 16진수 **앞 16자리**를 쓴다. `BattleContent.version()`이 제공하고 시작 로그에 출력한다.
  6. **시작 동작:** 시작 때 한 번 읽고 검증해 스프링 빈 하나(`BattleContent`)로 노출한다. 진단이 있으면 **모든 진단을 한 번에 로그로 남기고 서버를 시작하지 않는다.** 성공하면 번들 해시, 종류 수, 시작 공급 합계, 성장 단계 수를 INFO로 남긴다.
- **완료 조건:** 실제 `content.json`이 로드되고 요약이 맞다(스냅샷 테스트 `RealBattleContentTests`: 콘텐츠를 바꾸면 의도적으로 갱신). 6.3절의 규칙마다 **그 규칙만 어긴 JSON**이 해당 경로의 진단을 낸다(여러 오류는 모두 모아서, 1단계 오류가 있으면 2단계 진단은 나오지 않는다). **공유 테스트 벡터**와 CRLF, BOM 입력의 해시가 맞는다. `Growth`의 질의 메서드(`levelFor`의 목표 Level 정지와 목표 없는 성장도, `rewardFor`가 잔액이 목표보다 많을 때 0, 성장도 범위 밖)와 enum 이름 매핑이 테스트된다.

#### A4'. 클라이언트 번들 해시

- **목적:** 클라이언트가 자신의 콘텐츠 해시를 `battle` 엔트리에 싣는다. 에셋을 고치고 내보내기를 빠뜨렸을 때 서버가 알아챌 수 있게 하는 안전망이다. 해시는 **현재 에셋의 상태**를 가리켜야 한다.
- **런타임에 직렬화하지 않는다.** float를 문자열로 바꾸는 방식이 Mono, IL2CPP, ARM마다 마지막 자리가 달라질 수 있어, 기기에서 계산한 해시가 에디터가 내보낸 파일의 해시와 어긋나면 모든 `battle` 엔트리가 거절된다. 그래서 **에디터가 계산한 값을 빌드에 넣는다.**
- **요구사항**
  1. **빌드 시점:** 빌드 전처리기 (`IPreprocessBuildWithReport`)가 현재 에셋의 `ContentData`를 A1의 `ContentJson.Serialize`로 직렬화하고 `ContentJson.Version`을 구해 **텍스트 파일**(`Assets/Resources/BattleContentVersion.txt`)에 쓴다. 생성물이므로 `../../.gitignore`에 넣는다.
  2. **에디터 플레이 모드:** 플레이 모드에 들어가기 직전에 같은 계산을 해서 값을 넘긴다.
  3. **런타임 코드는 `ContentVersion.Current`(문자열) 하나만 읽는다.** 값의 출처(빌드 파일 또는 에디터)를 알 필요가 없다. `battle` 엔트리의 `contentVersion`에 쓴다.
  4. 값을 구할 수 없으면(파일 없음) **전투 엔트리를 만들지 않고** 오류를 로그로 남긴다(빈 해시를 서버에 보내지 않는다).
  5. 에셋을 수정하고 내보내지 않았다면 서버의 해시와 달라진다(의도된 동작, 시나리오 S6).
- **완료 조건:** 빌드 전처리기가 만든 값이 `ContentJson.Version(ContentJson.Serialize(현재 에셋))`과 같다(테스트). 내보낸 파일의 해시와 같은 값이다(에셋을 바꾸지 않은 경우). 에디터 플레이 모드와 빌드된 플레이어에서 `ContentVersion.Current`가 같은 값을 돌려준다. 파일이 없을 때 전투 엔트리가 만들어지지 않는다.

#### A7'. 노드 CSV 일치 확인과 편집 절차

- **목적:** 노드 데이터가 양쪽에서 같은 내용임을 확인하고, 이후 수정 절차를 정한다. 어긋나면 정상 구매가 거절된다.
- **요구사항**
  1. 서버 `src/main/resources/content/nodes/{UpgradeStats,Nodes,NodeCost,NodeEffects}.csv`, `layout.json`과 클라이언트 `Assets/Data/NodeTable/*.csv`, 노드 배치를 **각자의 로더로 읽은 결과**(수치 정의, 노드별 Rank의 비용과 효과, 배치와 선)를 비교한다. 서버 CSV는 머리칸을 영문으로 바꾼 것이라 바이트 비교는 하지 않는다.
  2. 차이가 있으면 서버 파일을 기준으로 클라이언트 사본을 맞춘다.
  3. 수정 절차를 클라이언트 README에 적는다: 클라이언트에서 편집 → `../../tools/import_node_content.py`로 서버 형식으로 변환 → 서버의 `RealNodeContentTests` 통과 → 두 저장소에 커밋. 이번 기간에는 노드 수치를 수정하지 않는다.
- **완료 조건:** "차이 없음" 비교 결과(날짜, 커밋)와 절차 문서가 있다.

### B. 동기화

| ID | 작업 | 영역 | 규모 | 선행 |
|---|---|---|---|---|
| B1 | 프로토콜 명세 | — | 완료 | |
| B2 | 서버 동기화 엔드포인트와 적용 골격 | S | 3.5 | |
| B3 | 서버 구매 검증기와 `reset` | S | 2 | B2(인터페이스) |
| B4 | 클라이언트 행위 로그 기록기와 재적용기 | C | 3 | |
| B5a | **클라이언트 API 계층과 인증** (신규) | C | 3 | |
| B5 | 클라이언트 동기화 클라이언트 | C | 3.5 | B4, B5a |
| F1 | 옛 API 제거와 문서 | S | 1.5 | B2, B3 |

#### B2. 서버 동기화 엔드포인트와 적용 골격

- **목적:** `POST /me/sync`를 받아 엔트리를 순서대로 적용하며 검증하고 결과를 돌려준다. 엔트리 종류별 검증은 핸들러가 맡고, B2는 흐름만 책임진다.
- **요구사항**
  1. **엔드포인트:** `POST /api/v1/me/sync`(인증 필요). 요청과 응답은 [api-spec.md](../api-spec.md)의 동기화 절을 따른다. 요청은 `type`으로 구분되는 엔트리 목록을 다형적으로 읽는다(Jackson 타입 정보, 알 수 없는 `type`은 그 엔트리를 `MALFORMED_ENTRY`로 거절).
  2. **DB(Flyway V3):** 계정별 마지막 동기화 응답을 저장하는 `sync_batch`(계정 ID, `batchId`, 응답 본문, 생성 시각), `player_progress.last_sync_at`(초기값은 계정 생성 시각). `revision`의 의미가 "적용된 엔트리 수"로 바뀌므로 `PlayerProgress`의 주석을 고친다.
  3. **처리 순서**(한 트랜잭션, 계정 행 잠금):
     1. 요청 형식 검사(엔트리 50개 초과나 본문 1 MB 초과는 `413 PAYLOAD_TOO_LARGE`, 필드 누락은 `400`).
     2. 계정 잠금. 같은 계정의 다른 동기화가 처리 중이면 `409 SYNC_IN_PROGRESS`(대기하지 않고 즉시 거절).
     3. `batchId`가 저장된 마지막 `batchId`와 같으면 **저장된 응답을 그대로 돌려준다**(멱등).
     4. `baseRevision`이 서버 `revision`과 다르면 **`BASE_MISMATCH`**: 엔트리는 적용하지 않고 서버 상태를 돌려준다.
     5. 엔트리를 순서대로 핸들러에 넘겨 **작업 상태 객체**에 적용한다. 첫 거절에서 멈추고 `PARTIAL`로 `rejected{index, code}`를 만든다.
     6. 적용된 엔트리 수만큼 `revision`을 올리고 상태를 저장한다(부분 적용도 커밋). `last_sync_at`을 현재 서버 시각으로 갱신한다.
     7. `claimed`가 있으면 서버 결과와 비교해 `claimMatched`를 채운다(`gold`, `growthStage`, 노드 Rank를 순서 무관하게 비교).
     8. 응답을 `sync_batch`에 저장하고 돌려준다. 응답에는 항상 `state`가 담긴다.
  4. **엔트리 핸들러 인터페이스**(계약 1): 엔트리 종류마다 `apply(작업 상태, 엔트리, 동기화 문맥) → 적용 | 거절(코드)`를 구현한다. **거절할 때는 작업 상태를 바꾸지 않는다.**
     - **작업 상태:** `gold`, `growthStage`, 산 노드(처음 산 순서를 유지하는 `nodeId → rank` 맵).
     - **동기화 문맥:** 계정 ID, 서버 시계(`Clock`), `last_sync_at`, 이 묶음에서 지금까지 적용된 전투 `elapsed`의 누적, 노드 트리, 전투 콘텐츠, 현재 번들 해시.
  5. **오류 코드:** `ErrorCode`에 `SYNC_IN_PROGRESS`(409), `PAYLOAD_TOO_LARGE`(413)를 추가한다. 엔트리 거절 코드는 열거형으로 둔다.
  6. 한도 값(50개, 1 MB)은 설정으로 뺀다.
- **완료 조건:** 단위 테스트(가짜 핸들러로 적용 루프: 전부 수락, 중간 거절, 첫 엔트리 거절), 통합 테스트(기존 Testcontainers 방식): 같은 묶음 재전송이 한 번만 적용된다, 낮은 `baseRevision`이 `BASE_MISMATCH`다, 동시 요청이 직렬화된다, 한도 초과가 413이다, `revision = baseRevision + applied`.

#### B3. 서버 구매 검증기와 `reset`

- **목적:** `purchase`와 `reset` 엔트리를 검증하고 적용한다. 클라이언트의 `NodePurchase`와 **같은 판정**이어야 한다.
- **요구사항**
  1. **재활용:** 서버에 이미 있는 `NodeTree.node(id)`, `NodeGraph.isRevealed(id, owns)`, `NodeDefinition.maxRank()/rankAt(rank)`를 쓴다. `owns`는 "Rank가 1 이상"이다(Rank 1을 사는 순간 선으로 이어진 노드가 드러난다).
  2. **검사 순서(첫 위반이 거절 코드):** 트리에 없는 노드 `UNKNOWN_NODE` → 이미 최대 Rank `MAX_RANK_REACHED` → 드러나지 않음 `NODE_LOCKED` → `rank`가 현재 Rank + 1이 아님 `RANK_MISMATCH` → Gold 부족 `NOT_ENOUGH_GOLD`. (클라이언트의 판정 순서는 최대 Rank → 숨김 → Gold다.)
  3. **적용:** Gold에서 그 Rank의 비용을 뺀다(`long`, 오버플로 안전). 노드의 Rank를 올리고, 처음 산 순서를 유지한다.
  4. **`reset`:** Gold 0, 성장도 초기값(0), 산 노드를 모두 비운다.
- **완료 조건:** 거절 코드마다 테스트가 있다. **클라이언트와의 일치:** 클라이언트 `NodePurchase`로 만든 구매 시퀀스(노드와 Rank, 기대 결과 코드) 20개 이상을 fixture로 받아 서버가 같은 결과를 내는지 확인한다. 배치되지 않은 노드는 `UNKNOWN_NODE`다.

#### B4. 클라이언트 행위 로그 기록기와 재적용기

- **목적:** 구매, 전투 결산, 새 게임을 잃지 않게 로컬에 기록하고, 서버 상태 위에 다시 적용할 수 있게 한다.
- **현황:** 로컬 진행 저장 `ProgressStore`(`Assets/Scripts/Save/ProgressStore.cs`)가 이미 있다. 본 파일, 직전 저장(`.prev`), 깨진 파일 보관의 패턴을 따른다.
- **요구사항**
  1. **엔트리 모델**(순수 C#, Unity 비의존): [api-spec.md](../api-spec.md)의 `purchase`, `battle`, `reset`. Newtonsoft로 직렬화하고 `type`으로 구분한다. `TypeNameHandling`은 쓰지 않는다(역직렬화 보안).
  2. **`LogStore`:** 파일 `persistentDataPath/sync/sync-log.json`. 내용: `formatVersion`, `confirmedRevision`, `confirmedState`(마지막으로 서버에서 받은 상태), `pending`(대기 엔트리), `inflight`(전송 중인 묶음의 `batchId`와 엔트리 수), `discarded`(폐기한 엔트리, 사유 코드, 시각; 최근 50개).
  3. **쓰기:** 임시 파일에 쓰고 flush한 뒤 교체한다(직전 파일은 `.prev`로 남긴다). **구매, 결산, 새 게임이 일어날 때마다 즉시 동기로** 쓴다. 앱이 내려갈 때만 저장하면 모바일에서 잃는다(`GameBootstrap.cs:129`는 `OnApplicationPause`에서 진행만 저장한다).
  4. **읽기와 복구:** 본 파일 → 읽지 못하면 깨진 파일을 보관(`.corrupt-<시각>`)하고 `.prev` → 둘 다 안 되면 "로그 없음"으로 시작한다. 이 경우 `confirmedRevision`을 알 수 없으므로 첫 동기화를 엔트리 없이 보내 서버 상태를 받는다(로컬의 미동기화 진행은 잃는다, 드문 경우로 수용).
  5. **API:** `Append(entry)`(쓰기가 끝난 뒤 반환), `Pending`, `ConfirmedRevision`, `Acknowledge(applied, newRevision, state)`, `Discard(fromIndex, code)`, `SetInflight(batchId, count)`/`ClearInflight()`.
  6. **`LogReplayer`:** `confirmedState`에 `pending`을 순서대로 로컬 규칙으로 다시 적용한다. `purchase` → `NodePurchase.TryPurchase`, `reset` → `ProgressSave.StartNew`, `battle` → Gold += `settledGold`, 성장도 = `growthStageAfter`(엔트리의 값을 쓰고 결산을 다시 계산하지 않는다). 재적용 중 구매가 거절되면 그 엔트리부터 폐기한다.
  7. **앱 시작 시 로컬 진행 재구성:** `confirmedState`에 `pending`을 재적용한 결과를 로컬 진행으로 쓴다. 기존 `ProgressStore` 파일은 이 결과의 캐시이고, 서로 어긋나면 로그 쪽이 우선한다.
  8. **연결 지점:** 구매(`ScreenFlow.NodeTreeView.cs:42`), 결산(`ScreenFlow.Battle.cs:56`의 `TryEndAsync` 직후), 새 게임(`ScreenFlow.ModeSelect.cs:66`)에서 로컬 진행을 바꾼 직후 `Append`한다. 포기한 판(`TryAbandonAsync`)은 기록하지 않는다.
- **완료 조건:** 쓰는 도중 예외를 주입한 뒤 재시작해도 마지막으로 완전히 쓴 상태가 복구된다. 깨진 파일 복구 경로가 테스트된다. 대기 엔트리 수백 개의 저장이 프레임을 끊지 않을 만큼 빠르다. 재적용 결과가 서버 규칙과 같은 상태를 만든다.

#### B5a. 클라이언트 API 계층과 인증 (신규)

- **현황:** 클라이언트에는 **네트워크와 인증 코드가 전혀 없다**(`UnityWebRequest`, `HttpClient`, `guestId` 사용처 없음). 서버의 게스트 인증은 구현되어 있으므로 클라이언트 쪽을 새로 만든다.
- **목적:** 서버 API를 호출하고 게스트 인증을 유지하는 계층. 오프라인에서도 게임이 정상 동작해야 한다.
- **요구사항**
  1. **구성:** HTTP를 추상화한 `IHttpTransport`(Unity 구현은 `UnityWebRequest`)와 그 위의 `ApiClient`, `AuthSession`. 테스트에서는 가짜 전송을 끼운다.
  2. **요청:** 기본 URL은 빌드 설정으로 두고, 모든 요청에 `X-Client-Version`(`Application.version`)과 `Authorization: Bearer`를 붙인다. JSON은 Newtonsoft. 타임아웃은 짧게(예: 10초).
  3. **결과 구분:** 성공, 서버 오류(HTTP 상태와 오류 `code`), **네트워크 오류**(연결 불가, 타임아웃)를 구분하는 결과 타입을 쓴다. 네트워크 오류는 예외가 아니다.
  4. **인증 흐름**([api-spec.md](../api-spec.md)의 토큰 흐름): 저장된 자격이 없으면 `register` 후 `login`, 있으면 리프레시 토큰으로 `refresh`(`INVALID_REFRESH_TOKEN`이면 `login`). `401 UNAUTHORIZED`면 `refresh` 후 같은 요청을 한 번 재시도한다. 갱신 응답의 새 리프레시 토큰은 **받는 즉시** 저장하고, 갱신 요청은 한 번에 하나만 보낸다(겹치면 재사용 감지로 토큰이 폐기된다).
  5. **자격 저장:** `guestId`, `guestSecret`, `refreshToken`은 `PlayerPrefs`가 아니라 앱 전용 파일에 저장한다(플랫폼 보안 저장소는 범위 밖, 한계로 문서화).
  6. **오프라인 시작:** 로그인이나 갱신이 네트워크 오류로 실패해도 게임은 그대로 진행되고, 연결되면 다시 시도한다.
  7. **가짜 서버**(계약 6): 명세를 구현한 인메모리 서버를 EditMode 테스트용으로 만든다. 인증(register, login, refresh 회전, 만료)과 `/me/sync`(`ACCEPTED`, `PARTIAL`, `BASE_MISMATCH`, 멱등)를 흉내 낸다. B5의 테스트가 이 서버를 쓴다.
- **완료 조건:** 가짜 서버로 최초 실행, 재시작, 토큰 만료, 갱신 회전, 네트워크 끊김 시나리오가 통과한다(서버 `FakeClient`/`Stage1ClientFlowTests`가 검증하는 흐름과 같다).

#### B5. 클라이언트 동기화 클라이언트

- **목적:** 대기 로그를 서버에 제출하고 응답에 따라 로컬 진행을 맞춘다.
- **요구사항**
  1. **묶음:** `pending`의 앞에서 최대 50개를 새 `batchId`로 묶는다. 전송 직전에 `inflight`(`batchId`, 엔트리 수)를 `LogStore`에 저장해, 앱이 재시작되어도 **같은 `batchId`와 같은 엔트리로** 다시 보낸다. 응답을 받기 전에는 `pending`에서 지우지 않는다. 그동안 쌓인 새 엔트리는 다음 묶음이다.
  2. `baseRevision`은 `confirmedRevision`이고, 이어지는 묶음은 직전 응답의 `revision`이다. `claimed`는 이 묶음 이후 대기 로그가 없을 때만 로컬 진행으로 채운다.
  3. **응답 처리**([api-spec.md](../api-spec.md)의 클라이언트 처리 흐름):

     | 응답 | 처리 |
     |---|---|
     | `ACCEPTED` | `Acknowledge`, 서버 `state` 채택 후 남은 `pending`을 재적용 |
     | `PARTIAL` | 서버 `state` 채택, **대기 로그를 모두 폐기**(로컬에 보관), 사용자에게 안내 |
     | `BASE_MISMATCH` | 서버 `state` 채택, 대기 로그 폐기(보관), 사용자에게 안내 |
     | 네트워크 오류, `5xx`, `409 SYNC_IN_PROGRESS` | 같은 `batchId`로 재시도(백오프) |
     | `413` | 묶음을 반으로 나눠 재시도 |
     | `401 UNAUTHORIZED` | 토큰 갱신 후 한 번 재시도 |
  4. **서버 상태 채택:** `ProgressSave.Restore`로 로컬 진행을 덮어쓰고 `LogStore.confirmedState`를 갱신한다. **`ProgressStore.Save`는 롤백을 되돌려 적는다**(`ProgressSave.Capture`가 "진행은 줄지 않는다"는 전제로 원래 값과 지금 값 중 큰 쪽을 적는다). 서버가 낮은 값으로 되돌렸을 때 이전 값이 파일에 되살아나지 않도록, 상태를 채택할 때 `ProgressStore`의 `_basis`를 초기화하는 `AdoptServerState` 같은 경로가 필요하다.
  5. **사용자 안내:** `PARTIAL`과 `BASE_MISMATCH`로 진행이 취소되면 **한 번 확인을 받는 창**으로 알린다("서버 검증에서 일부 진행이 취소되었습니다"). 취소된 구매 수, 전투 수와 잃은 Gold를 요약하고, 거절 코드별 문구를 둔다. 조용히 되돌리지 않는다.
  6. **트리거:** 앱 시작과 로그인 직후, 앱 복귀(`OnApplicationPause(false)`), 네트워크 연결 회복, 구매와 결산 직후(온라인이면 즉시), 오프라인 중 주기적 시도(예: 60초, 실패하면 최대 5분까지 지수 백오프). 동기화는 **동시에 하나만** 실행한다.
  7. 처음 설치했거나 로컬 진행이 없는 기기는 엔트리 없이 동기화해 서버 상태를 받는다.
  8. `SyncClient`는 Unity 비의존 순수 C#으로 만들고, 전송은 B5a의 `IHttpTransport`를 쓴다.
- **완료 조건:** 가짜 서버로 시나리오 S1~S5가 통과한다(오프라인 쌓기와 제출, 부분 거절과 상태 채택, 분기, 재시작 후 같은 묶음 재전송, 응답 유실). 서버 롤백 후 앱을 재시작해도 이전 값이 되살아나지 않는다.

#### F1. 옛 API 제거와 문서

- **목적:** 검증을 우회하는 경로를 없애고 문서를 현실에 맞춘다.
- **요구사항**
  1. `PUT /me/progress`, `POST /me/progress/reset`과 관련 DTO(`SaveProgressRequest`), 서비스 메서드(`ProgressService.save/reset`)를 **제거한다.** 남아 있으면 클라이언트가 진행도를 직접 덮어쓸 수 있다.
  2. `ErrorCode`에서 `STALE_PROGRESS`, `PROGRESS_REGRESSION`을 제거한다.
  3. 테스트를 새 흐름으로 교체한다: `ProgressApiTests`(조회만 남김), `Stage1ClientFlowTests`와 `FakeClient`를 동기화 흐름(오프라인 쌓기, 부분 거절, 분기)으로 바꾼다.
  4. [api-spec.md](../api-spec.md)의 구현 현황과 [node-content.md](../node-content.md)를 고친다("클라이언트는 노드의 비용·효과·해금 규칙을 가지지 않는다"와 클라이언트 사본 동결 문장).
- **완료 조건:** 옛 두 경로가 `404` 또는 `405`다(S7). 빌드와 전체 테스트가 통과한다.

### C. 전투 L1 검증

| ID | 작업 | 영역 | 규모 | 선행 |
|---|---|---|---|---|
| C0 | 대응표와 기대값 fixture | C | 1.5 | |
| C1 | `battle` 엔트리 구성과 골든 케이스 | C | 3.5 | B4(인터페이스) |
| C2 | 서버 스탯 표 계산기 | S | 2 | C0(fixture) |
| C4a | 서버 공급 계산 | S | 1.5 | C0, A2, C2 |
| C4b | 서버 허용 범위 계산 | S | 1.5 | C0, A2, C2 |
| C3 | 서버 L1 결산 검증 핸들러 | S | 4.5 | A2, C2, C4a, C4b, B2(인터페이스) |

#### C0. 대응표와 기대값 fixture

- **목적:** 노드 수치가 전투 규칙에 어떻게 쓰이는지를 서버 구현자에게 정확히 전달하고, 서버가 클라이언트와 같은 값을 내는지 확인할 기준 데이터를 만든다. 서버 규칙 이식의 **정답지**다.
- **출력**
  1. **대응표 문서**(`docs/battle-stat-map.md`): `UpgradeStats.csv`의 60개 수치(`statId`)가 어느 규칙에 어떻게 쓰이는지(늘어난 양 `GainOf`를 더하는지, 값 자체 `ValueOf`를 쓰는지, 반올림 규칙, 상한). 근거는 클라이언트의 `EnemyUpgradeStats`, `HqUpgradeStats`, `BreakerDefinition.Upgraded`, `TimeLimitDefinition.Upgraded`, `EnemyComposition.From`, `GameSessionFactory`다.
  2. **기대값 fixture**(서버 저장소 `src/test/resources/fixtures/battle-composition.json`): 노드 구성(`{nodeId: rank}`) 여러 개와, 클라이언트 **실제 코드가 계산한** 기대값. 항목: 전체 스탯 표(`statId` → 값), 종류별 시작 공급과 성장 공급(변환 반영), 종류별 질량과 색 비율, 크기, 성질 확률, 황금 수치(배율, 치명 확률, 치명 보상 배율), 재생성 확률, 시간 추가 확률, 제한 시간(기본, Level당 성장 시간, 처치당 시간 추가 초).
- **요구사항**
  1. 구성은 **12개 이상**: 노드 없음, 최대 구매, 각 계열 대표 노드(`asteroid.count`, `asteroid.toPlanet`, `planet.toStar`, `growth.*`, `*.massScale`, `*.size`, `*.respawnChance`, `*.timeChance`, 황금 계열, 달 `maxCount`, 혜성 계열, `timer`, `growth.time`).
  2. 내보내기 도구는 에디터 메뉴나 EditMode 테스트로 만들고, 같은 방식으로 새 구성을 추가할 수 있게 한다.
  3. 소수는 float 그대로가 아니라 **왕복 가능한 십진수 문자열**로 낸다(서버가 `BigDecimal`로 읽는다).
- **완료 조건:** fixture가 서버 저장소에 있고 대응표가 리뷰되었다. 서버 구현자가 대응표만으로 C2, C4a, C4b를 구현할 수 있다.

#### C1. `battle` 엔트리 구성과 골든 케이스

- **목적:** 한 판의 시작 스냅샷과 결산을 [api-spec.md](../api-spec.md)의 `battle` 엔트리로 만들어 결산 시점에 로그에 기록한다. 서버 검증이 쓸 **킬 장부**가 핵심이다.
- **요구사항**
  1. **킬 장부:** 처치를 `(종류, Tier, 크기, 황금)`별로 세고, 황금 보너스 치명타 수(`goldenCrits`)를 센다. 현재 `EnemyRoster`는 종류별로만 센다(`_killsByKind`, `GetKillCounts()`). 기존 API는 유지하고 장부용(`GetKillLedger()`)을 더한다. Tier, 크기, 황금이 장부에 필요한 이유는 Gold가 Tier에 따라 1에서 7776까지 6배씩 차이 나고, 크기 k는 Gold와 EXP에 k배, 황금은 Gold 배율과 보너스 치명타가 붙기 때문이다(`EnemyDefinition.StatsAt`, `SizeRule`, `DeathRewards`).
  2. **시작 스냅샷:** `GameSessionFactory.Create`가 만드는 값을 불변 스냅샷으로 노출한다: `growthStage`, `upgrades`(60개 수치 전부, `statId` → 값), `supply`(`StartSupplyOf` 결과: 변환까지 반영한 종류별 시작 수), `seed`.
  3. **결산:** `BattleRawData`에서 `elapsed`, `earnedGold`, `exp`, `level`, `settledGold`, `growthStageAfter`(`NextStage`)를 가져오고, **종료 사유**(`Milestone`: 이정표에 닿음, `TimeUp`: 제한 시간 만료, `Manual`: 직접 종료)를 `BattleRawData`에 더한다.
  4. `contentVersion`은 A4'의 값이다. `battleId`는 새 UUID.
  5. **기록 시점:** `ScreenFlow.Battle.cs:56`의 `TryEndAsync`가 결산을 끝낸 직후, 로컬 진행을 저장하기 전에 `LogStore.Append`한다(결산과 로그 기록이 한 쌍). 포기한 판은 엔트리를 만들지 않는다.
  6. **골든 케이스:** 서버 검증을 보정할 **정상 판의 L1 엔트리**를 만든다. `Core`가 Unity에 의존하지 않으므로, EditMode 테스트에서 `GameSession`을 **헤드리스로** 돌린다(조준점은 가장 가까운 적을 따라가는 단순한 정책). 노드 구성(C0의 구성)과 시드를 바꿔 **판 수십 개**를 만든다. 이정표에 닿는 판, 황금과 큰 크기가 나오는 판, 시간 추가가 많이 나오는 판을 포함한다. 가능하면 실제로 플레이한 판도 몇 개 더한다. 출력은 서버 저장소 `src/test/resources/fixtures/battle-golden/*.json`이다(노드 구성과 엔트리를 한 쌍으로).
- **완료 조건:** 클라이언트 쪽 불변식이 테스트된다(장부의 Gold 합 + 황금 보너스 == `earnedGold`, 장부의 EXP 합 == `exp` − 시작 EXP). 골든 케이스 파일이 있고, 서버 C3가 이를 전부 통과한다. `battle` 엔트리가 `LogStore`에 기록되고 B5가 제출한다.

#### C2. 서버 스탯 표 계산기

- **목적:** 노드 Rank에서 완성된 스탯 표를 계산해, 클라이언트가 보낸 `start.upgrades`와 대조한다.
- **요구사항**
  1. **재활용:** `NodeContent.stats()`, `UpgradeStatDefinition`(기본값, 최소, 최대, `clamp`), `NodeDefinition.rankAt(r).effects`.
  2. **계산**(클라이언트 `NodePurchase.StatsFor`): 배치된 노드마다 Rank 1부터 산 Rank까지의 효과 값을 `statId`별로 합하고, 값 = `clamp(기본값 + 합)`. 모든 수치(60개)에 대해 값을 낸다(효과가 없으면 기본값). `BigDecimal`로 합산한다.
  3. **제공 형태:** `valueOf(statId)`(전체 값), `gainOf(statId)`(늘어난 양 = 값 − 기본값). C4a, C4b, C3가 쓴다.
  4. **대조:** `start.upgrades`가 같은 `statId` 집합이고 값이 같은지 확인한다. float로 읽는 클라이언트와의 차이를 위해 상대 오차 `1e-4`(정수 수치는 정확히)를 허용한다. 누락, 모르는 `statId`는 불일치다.
- **완료 조건:** C0의 fixture 전체가 일치한다. 최소와 최대 자르기(예: `breaker.critChance`의 100 상한)와 정수 수치가 테스트된다.

#### C4a. 서버 공급 계산

- **목적:** 판의 시작 공급과 레벨업당 성장 공급을 계산한다. 시작 스냅샷 대조(`start.supply`)와 공급 상한(C3)의 입력이다. 클라이언트 `GameSessionFactory.StartSupplyOf`, `GrowthSupplyOf`를 옮긴다.
- **규칙**
  1. 콘텐츠의 시작 공급을 종류별로 합산한다(순서는 처음 나온 순서).
  2. 종류마다 시작 공급 보너스 `Whole(gain(asteroid.count))`(소행성만)를 더한다(0보다 클 때). 콘텐츠에 없던 종류는 뒤에 붙는다.
  3. **변환:** 변환 사슬을 위상 순서로(소행성 → 행성, 그다음 행성 → 별) 돌며 `moved = min(보유 수, 변환 수)`를 다음 종류로 옮긴다. 변환 수는 `Whole(gain(asteroid.toPlanet))`, `Whole(gain(planet.toStar))`. 행성 → 별은 소행성에서 바뀐 행성까지 센다.
  4. 수가 0이 된 종류는 뺀다.
  5. **성장 공급(레벨업 한 번):** 변환 반영된 종류별 시작 수 × `max(0, gain(growth.asteroids/planets/stars)) / 100`을 반올림(`Whole`: 0.5는 올림). 0마리인 종류는 뺀다.
- **`Whole`** 은 0에서 먼 쪽으로 반올림(half away from zero)이다.
- **완료 조건:** C0 fixture의 시작 공급과 성장 공급이 일치한다. 변환이 보유 수보다 클 때, 변환 대상이 콘텐츠에 없을 때의 경계가 테스트된다.

#### C4b. 서버 허용 범위 계산

- **목적:** 종류별 판 구성(질량에 따른 색 분포, 크기, 성질 확률, 재생성과 시간 추가 확률)을 계산해 처치 상한과 장부의 허용 범위를 만든다. 클라이언트 `EnemyComposition.From`, `MassRule`, `SizeRule`을 옮긴다(색 분포의 **정확한 재현**은 하지 않고 허용 범위만 쓴다).
- **규칙**
  1. **색 비율(`MassRule`):** 질량 `m = 질량(%) / 100`. 색 `c`(0부터)의 비율 = `[m − 2, m]` 구간과 칸 `[c, c+1]`이 겹치는 길이 ÷ 2. 축 밖으로 나간 부분은 양 끝의 색(0 아래는 첫 색, 색 수 위는 마지막 색)에 붙인다. **질량은 `ValueOf`(값 자체)** 를 쓴다(소행성 기본 100, 행성 기본 0, 별 기본 100). 나오는 Tier 집합 = 비율이 0보다 큰 색.
  2. **크기:** `Whole(1 + gain(*.size))`, 상한 20(`SizeRule.Max`). 크기 1부터 이 값까지가 같은 몫으로 나온다.
  3. **성질 확률:** `min(1, gain(*.spawnChance) / 100)`(예: `goldenAsteroid.spawnChance`).
  4. **황금 수치:** 배율 = 콘텐츠의 `Multiplier` + `gain(goldenAsteroid.rewardScale)/100`, 치명 확률 = `min(1, 콘텐츠 + gain(critChance)/100)`, 치명 보상 배율 = 콘텐츠 + `gain(critRewardScale)/100`.
  5. **재생성 확률, 시간 추가 확률:** `min(1, gain(*.respawnChance)/100)`, `min(1, gain(*.timeChance)/100)`. 픽업(혜성)은 해당 없음.
  6. **혜성:** 출현 확률 `gain(comet.spawnChance)/100`, 혜성 비 확률 `gain(comet.rainChance)/100`.
- **완료 조건:** C0 fixture와 일치한다(색 비율은 소수 5자리). 질량 0, 150, 250, 700% 이상 등 경계와 크기 상한이 테스트된다.

#### C3. 서버 L1 결산 검증 핸들러

- **목적:** `battle` 엔트리를 검증하고 진행 상태에 반영한다. 클라이언트가 주장한 `earnedGold`, `settledGold` 등은 **검증 대상일 뿐 정산에 쓰이지 않는다.**
- **검사 순서와 코드**
  1. `contentVersion`이 서버 번들 해시와 다르면 `CONTENT_VERSION_MISMATCH`.
  2. 형식(종류, Tier, 크기가 콘텐츠와 판 구성의 범위 안, `count ≥ 0`, `goldenCrits ≤ count`, 황금은 황금 성질이 있고 확률이 0보다 클 때만): 위반은 `MALFORMED_ENTRY`.
  3. **시작 스냅샷:** `start.growthStage`가 상태와 같고, `start.upgrades`가 C2의 표와 같고, `start.supply`가 C4a의 시작 공급과 같다. 아니면 `BATTLE_START_MISMATCH`.
  4. 이하는 위반 시 `BATTLE_IMPLAUSIBLE`.
- **검사 내용**
  1. **장부(정확):** 종류 `k`, Tier `t`, 크기 `s`의 Gold `g = round(Tier Gold × s)`, 황금이면 `g = round(g × 황금 배율)`(half up). 황금 보너스 하나 = `round(g × 치명 보상 배율)`. `earnedGold = Σ 수량 × g + Σ goldenCrits × 보너스`. 허용 오차는 황금 처치당 ±1 Gold(float 반올림). `exp = 시작 Level의 누적 EXP + Σ 수량 × (Tier Exp × s)`가 정확히 같다.
  2. **Level:** 시작 Level = 성장도에 따른 시작 Level, `level` = `exp`에서 사다리로 도출(`exp ≥ ExpToReach(L)`인 가장 큰 `L`), **목표 Level(다음 이정표)에서 멈춘다.**
  3. **결산(정확):** `ended = Milestone`이면 `level`이 목표 Level이고, 결산 Gold = `max(0, 이정표 TargetGold − 현재 Gold)`(그 판에서 번 Gold는 버린다), `growthStageAfter = 성장도 + 1`. 목표가 없는 성장도(마지막 이정표 뒤)에서 `Milestone`은 위반이다. 아니면 결산 Gold = `earnedGold`, 성장도는 그대로다.
  4. **공급 상한:** `levelsRaised = level − 시작 Level`. 종류 `k`의 공급 `S_k = 시작 수_k + levelsRaised × 성장 공급_k`(C4a), 재생성 확률 `p_k`, **생성 상한 `ceil((S_k + 여유) ÷ (1 − p_k))`**. 처치 수가 이 상한을 넘으면 위반. 여유는 통계 여유(`4·√S_k + 4` 같은 식)로 시작해 골든 케이스로 보정한다. `p_k ≥ 0.99`이면 종류별 상한을 건너뛰고 총 처치 수를 "초당 최대 처치 수 × `elapsed`"(골든 케이스에서 정하는 상수)로 묶는다. Tier, 크기, 황금별 처치 수는 색 분포 허용 범위(`QuotaPicker`가 비율에 가깝게 배분하므로 `ceil(생성 상한 × 비율) + 2`), 크기는 `ceil(생성 상한 ÷ 최대 크기) + 2`, 황금은 `ceil(생성 상한 × 황금 확률) + 2` 안이어야 한다. **이 검사가 없으면 "소행성 100만 마리를 잡았다"는 주장을 막을 방법이 없다.**
  5. **시간 상한:** `elapsed ≤ BattleRules.TimeLimit + gain(timer) + levelsRaised × gain(growth.time) + 시간 추가 최대 횟수 × KillTimeBonus + 프레임 여유(0.5초)`. 시간 추가 최대 횟수 = `Σ_k ceil(일반 적 처치 수_k × timeChance_k + 통계 여유)`.
  6. **누적 시간:** 마지막 동기화 이후 이 묶음에서 적용된 `elapsed`의 합 ≤ (서버 현재 시각 − `last_sync_at`) + 여유(5초). 첫 동기화는 계정 생성 시각이 기준이다.
  7. **혜성:** 혜성 처치 수 ≤ `floor(elapsed ÷ 출현 주기) × max(1, 혜성 비 수)` + 1.
- **반영:** `gold += 결산 Gold`, `growthStage = growthStageAfter`.
- **로그:** 거절하면 어느 검사에서 무엇이 기대와 달랐는지(기대값, 실제값, `accountId`, `battleId`)를 서버 로그에 WARN으로 남긴다. 응답에는 코드만 담는다.
- **튜닝 상수는 한 곳**(`BattleVerificationPolicy`: 통계 여유 계수, 프레임 여유, 초당 최대 처치 수, 누적 시간 여유)에 둔다.
- **완료 조건:** C1의 골든 케이스가 **전부** 통과한다(오탐 0). 의도적으로 조작한 엔트리(과장된 `kills`, 높은 Tier 주장, 잘못된 업그레이드 표, 잘못된 시작 공급, 시간 초과, `goldenCrits` 과다, 이정표 위조)가 모두 올바른 코드로 거절된다.

### E. 조건부

| ID | 작업 | 규모 | 조건 |
|---|---|---|---|
| E1 | 랭킹: `POST /me/ranking`(닉네임), `GET /rankings`. 서버가 검증된 상태로 점수를 계산 | 서버 3 + 클라이언트 1 | 필수 작업의 진척이 계획보다 앞서 있을 때(중간 점검에서 판단) |
| E2 | 도전과제와 달성률 | 서버 3 + 클라이언트 1 | E1을 넣고도 여유가 남을 때 |

필수 범위가 용량을 거의 채우므로 **넣지 못할 가능성이 높다.** 못 넣으면 archive 7장의 스케치가 기록으로 남는다.

## 5. 작업 관계와 통합 지점

일정은 정하지 않고 **선행 관계**만 적는다.

```
A1 ──▶ A2 ──┬──▶ C4a ─┐
   └─▶ A4'  └──▶ C4b ─┼──▶ C3 ──▶ 통합 2
C0 ──▶ C2 ──────────────┤
B2(인터페이스) ─────────┘
B2 ──▶ B3
B4 ──┬──▶ B5 ──▶ 통합 1
B5a ─┘
B4 ──▶ C1 ──▶ 통합 2
통합 1, 통합 2 ──▶ 통합 3(시나리오) ──▶ F1(최종 정리)
A7'는 독립
```

- **병렬로 시작할 수 있는 묶음:** (A1, A4', A7') · (B2 → B3) · (B4, B5a → B5) · (C0 → C2 → C4a, C4b) · (C1). 계약(3장)만 맞추면 서로를 기다리지 않는다.
- **서버 쪽 임계 경로:** A1 → A2 → C4a, C4b → C3. 가장 긴 연쇄(약 12.5인일)이므로 이 묶음을 먼저 착수한다.
- **클라이언트 쪽 임계 경로:** B4 → B5, C1. B5a는 B4와 병렬이다.
- **통합 1 (구매 end-to-end):** B2, B3와 B4, B5a, B5를 붙여 오프라인 구매, 재접속 동기화, 분기 거절을 확인한다.
- **통합 2 (전투 L1 end-to-end):** C1과 C3를 붙이고 골든 케이스로 오탐을 확인한다.
- **통합 3 (시나리오):** 아래 S1~S7 전부.

**규모 합계:** 클라이언트 약 18인일(A1 1.5, A4' 1, A7' 0.5, C0 1.5, C1 3.5, B4 3, B5a 3, B5 3.5), 서버 약 19.5인일(A2 3, B2 3.5, B3 2, C2 2, C4a 1.5, C4b 1.5, C3 4.5, F1 1.5). 합계 약 37.5인일에 통합과 수정(15% 안팎, 약 5.5인일)을 더하면 약 43인일이다. 클라이언트 API 계층(B5a)이 코드에 없어서, 그리고 클라이언트 번들 해시(A4')를 빌드 시점 계산으로 바꾸면서 처음 추정보다 약 3.5인일이 늘었다.

## 6. 완료 정의 (시나리오)

| # | 시나리오 | 확인할 것 |
|---|---|---|
| S1 | 온라인에서 구매 | 서버 `revision`이 오르고 서버 상태와 클라이언트 상태가 같다 |
| S2 | 오프라인에서 구매 3건과 전투 2판 후 재연결 | 모두 `ACCEPTED`, 서버의 Gold와 성장도가 클라이언트와 같다 |
| S3 | 조작된 엔트리(Gold 부족 구매, 과장된 kills, 높은 Tier 주장, 잘못된 업그레이드 표, 시간 초과) | `PARTIAL`로 거절되고 클라이언트가 서버 상태로 되돌아가며 사용자에게 안내한다. 앱을 재시작해도 되살아나지 않는다 |
| S4 | 낡은 로컬 진행(다른 기기나 백업 복원) | `BASE_MISMATCH`로 서버 상태를 채택한다 |
| S5 | 응답 유실 후 같은 묶음 재전송(앱 재시작 포함) | 한 번만 적용된다 |
| S6 | 콘텐츠를 수정하고 내보내기를 빠뜨린 클라이언트의 `battle` | `CONTENT_VERSION_MISMATCH`로 거절된다 |
| S7 | 옛 API 호출 | `PUT /me/progress`, `POST /me/progress/reset`이 없다(404 또는 405) |

## 7. 위험과 대응

- **오탐(정상 유저의 롤백)이 가장 큰 위험이다.** 정상 판이 거절되면 사용자의 진행이 되돌아간다. C1의 골든 케이스로 C3를 검증하고 여유 계수를 보정하는 것을 완료 조건으로 둔다.
- **서버의 전투 검증 연쇄(A2 → C4 → C3)가 가장 길다.** 밀리면 C3의 6번(누적 시간)과 7번(혜성)을 뒤로 돌린다(둘 다 개별 검사라 독립적이다). 클라이언트 쪽의 여유는 서버 코드를 대신 짜기 어려우므로 fixture, 골든 케이스, 통합 테스트로 서버를 돕는다.
- **클라이언트 합계가 커졌다.** B5a가 새로 필요하고 B5가 가장 큰 단일 작업이다. 클라이언트 폴리싱과 겹치면 통합 1 전에 진척을 확인하고, 필요하면 C1 일부를 나눈다.
- **서버 롤백이 로컬 저장소에 되살아나는 문제**(`ProgressSave.Capture`의 "큰 값을 되돌려 적는" 동작)를 B5에서 반드시 처리해야 한다. 놓치면 S3에서 드러난다.
- **노드 데이터가 어긋나면 정상 구매가 거절된다.** A7'로 한 번 맞추고 이번 기간에는 노드 수치를 수정하지 않는다.
- **내보내기를 빠뜨린 클라이언트는 S6처럼 드러난다.** 서버를 먼저 배포한다는 운영 규칙을 지킨다. 서버가 읽지 않는 필드(이동 속도 등)만 바꿔도 번들 해시가 바뀌므로 서버 재배포가 필요하다(파일 전체가 해시 대상이다).
- **빌드 해시 파일이 없는 빌드는 전투 엔트리를 만들지 않는다**(A4'). 에디터를 거치지 않고 만든 빌드로는 전투 진행이 서버에 올라가지 않으므로, 배포 빌드는 에디터 빌드 파이프라인을 거쳐야 한다.
- **Newtonsoft는 전이 의존이다.** A1에서 `manifest.json`에 명시 추가하지 않으면 다른 패키지를 정리할 때 사라질 수 있다.

**모바일 대상 고려** (범위 안)

| 영향 | 내용 | 작업 |
|---|---|---|
| 로그를 즉시 영속 저장 | 모바일 OS는 앱을 언제든 종료한다. 로그는 `OnApplicationPause`만 믿지 않고 **구매와 결산마다 즉시, 원자적으로** 쓴다. 재시작 때 쓰다 만 파일을 복구한다 | B4 |
| 재시도 | 요청이 끊기는 일이 흔하므로 멱등(`batchId`)은 필수다. 전송 중인 묶음을 저장해 재시작 후에도 같은 묶음을 다시 보낸다 | B2, B5 |
| float 오차 | ARM64와 IL2CPP에서는 곱셈과 덧셈이 합쳐져 마지막 자리가 달라질 수 있다. 서버는 float 값의 정확한 동등 비교를 하지 않고(스탯 표, 시간 상한, 황금 배율 반올림 등) 허용 오차를 둔다 | C2, C3 |
| 기기 변경과 재설치 | 게스트 자격과 아직 동기화하지 않은 로그를 잃는다. 이번에는 수용한다 | (한계) |
| 전투 중 백그라운드 | 전투는 자동 일시정지되지 않고 앱이 멈추는 동안 프레임도 멈춘다. 전투 중 앱이 종료되면 그 판은 기록하지 않는다 | (참고) |

## 8. 여유가 생기면 archive에서 가져올 순서

1. **E1 랭킹** → archive 7장
2. **B6 단일 기기 로그인** → archive 4장
3. **재시도 가능 거절, `/system/status`, 콘텐츠 이력** → archive 6장
4. **A4/A5 서버 JSON 정본 방향** → archive 5장
5. **L3 이벤트 검증** → archive 2장 (가장 크고 마지막)
