# 전투 콘텐츠 계약 (A1 ↔ A2)

- 상태: **계약 초안.** 클라이언트가 내보내는 `content.json`(A1)과 서버가 만드는 `BattleContent` 객체(A2)의 형태를 정한다. 작업 정의는 [offline-sync-work-breakdown.md](offline-sync-work-breakdown.md)에 있고, 이 문서는 그 작업 중 A1, A2, A4'의 **세부 계약**이다.
- 근거 코드: 클라이언트 `Assets/Scripts/Core/Battle/Data/`, `Core/Enemies/Data/`, `Core/Hq/Data/`의 데이터 클래스와 로더(2026-10-12 시점).
- **작업 문서와 달라지는 점**이 있다([10장](#10-작업-문서와-달라지는-점)). 특히 A4'(클라이언트 번들 해시)는 런타임 직렬화가 아니라 **빌드 시점 계산**으로 바뀐다.

## 1. 한눈에 보기

```
[Unity 에셋] ──ToData()──▶ ContentData ──ContentLoader(검증)──▶ (통과해야 내보냄)
                              │
                       ContentJson.Serialize (에디터)
                              │
                              ▼
          content.json (서버 저장소) ──── 같은 바이트 ───▶ 번들 해시 (서버 계산)
                              │                              ▲  같은 값이어야 한다
                              ▼                              │
        서버 로더(A2) ──▶ BattleContent(불변 객체)     빌드 시 에디터가 계산해 빌드에 넣은 해시(클라이언트)
```

- **계약하는 것 세 가지:** ① 파일의 모양(JSON 스키마와 바이트 규칙), ② 번들 해시 알고리즘, ③ 서버 객체의 형태와 검증 규칙.
- 서버는 이 파일에서 **전투 검증에 필요한 일부만** 읽는다. 나머지(이동 속도, 반지름, 사망 효과의 피해 수치, 브레이커, 출현 띠)는 읽지 않지만 **해시에는 들어간다**(파일 전체가 해시 대상이다).

## 2. 파일 규약

| 항목 | 규칙 |
|---|---|
| 경로 | 서버 저장소 `src/main/resources/content/battle/content.json` |
| 인코딩 | UTF-8, **BOM 없음** |
| 줄바꿈 | **LF**, 파일 끝에 개행 하나 |
| 들여쓰기 | 공백 2칸 |
| 최상위 | `{ "FormatVersion": 1, "Content": { … } }` |

- `FormatVersion`은 **정수 1**이다. 값이 다르면 서버는 시작하지 않는다. 스키마가 바뀌었을 때 서버가 조용히 필드를 놓치는 일을 막는다.
- `Content`는 클라이언트 `ContentData`(`BattleRules`, `Breaker`, `Enemies`, `Growth`)와 **필드 이름과 중첩이 같다.** 주의: `ContentData.Enemies`의 타입이 `EnemyContentData`이고 그 안에 다시 `Enemies` 목록이 있어서 경로가 `Content.Enemies.Enemies[]`가 된다. 이름을 바꾸지 않는다.

## 3. 번들 해시

**알고리즘** (서버와 클라이언트가 같은 결과를 내야 한다)

1. 파일 바이트를 읽는다(클라이언트는 직렬화한 문자열의 UTF-8 바이트).
2. 맨 앞의 UTF-8 BOM(`EF BB BF`)이 있으면 제거한다.
3. `\r\n`을 `\n`으로 바꾼다(단독 `\r`은 그대로).
4. SHA-256을 구한다.
5. 소문자 16진수 문자열의 **앞 16자리**가 번들 해시(`contentVersion`)다.

**공유 테스트 벡터** (양쪽 테스트가 모두 이 값을 확인한다)

입력 문자열 `{\n  "FormatVersion": 1\n}\n`(25바이트, LF) →

- SHA-256: `a4ed84f2186aeaca71f6af46e9aadf82a08263498b3ca6e7b8ecd6305f98ec89`
- 번들 해시: **`a4ed84f2186aeaca`**
- 같은 내용을 CRLF로 바꾼 입력, BOM을 붙인 입력도 같은 값이어야 한다.

**결과적인 성질**

- 서버가 쓰지 않는 필드(이동 속도, 반지름 등)를 바꿔도 해시가 바뀐다. 그러면 새 클라이언트의 `battle` 엔트리가 거절되므로 **콘텐츠를 바꾸면 서버 재배포가 필요하다.** 이번 범위의 운영 규칙(내보내기 → 서버 배포 → 클라이언트 배포)과 같은 방향이라 수용한다.
- **해시는 런타임에 직렬화해서 계산하지 않는다.** float를 문자열로 바꾸는 방식이 플랫폼(Mono, IL2CPP, ARM)마다 마지막 자리가 달라질 수 있어, 기기에서 계산한 해시가 에디터가 내보낸 파일의 해시와 어긋날 위험이 있다. 클라이언트의 해시는 **에디터가 계산한 값을 빌드에 넣어** 쓴다(5.4).

## 4. JSON 스키마

### 4.1 형식 규칙

| 항목 | 규칙 |
|---|---|
| enum | **이름(문자열)**: `"Asteroid"`, `"Planet"`, `"Star"`, `"Comet"` / `"Golden"`, `"Electric"`, `"Moon"`, `"Laser"`, `"Supernova"`, `"Comet"`. 대소문자를 구분한다. 정수로 내보내지 않는다 |
| null | `UpgradesTo`는 변환이 없으면 `null`. 그 외 필드에는 `null`을 쓰지 않는다 |
| 정수 | `int`, `long`은 정수 리터럴 |
| 실수 | `float`는 Json.NET의 왕복 표기(`1.5`, `0.312`). 정수 값도 `1.0`처럼 소수점이 붙고, 아주 작은 값은 `1E-05`처럼 지수 표기가 나올 수 있다(유효한 JSON). 서버는 `BigDecimal`로 읽는다 |
| 필드 순서 | DTO 선언 순서(결정적임은 왕복 테스트로 확인) |
| 목록 순서 | **의미가 있다.** `Enemies.Enemies`의 순서(종류가 처음 나온 순서)와 `StartSupply`의 순서를 바꾸지 않는다 |
| 알 수 없는 필드 | 서버는 무시한다. 클라이언트가 필드를 추가해도 서버가 깨지지 않는다(해시는 바뀐다) |

### 4.2 필드 표

**서버** 열: **R** = 서버가 읽고 계산에 쓴다, **V** = 읽어서 검증만 한다, **–** = 읽지 않는다(해시에는 들어간다).

| 경로 (`Content.` 아래) | JSON 타입 | 서버 | 의미 |
|---|---|---|---|
| `BattleRules.TimeLimit` | number | R | 기본 제한 시간(초). 노드 `timer` 수치와 합쳐 이 판의 제한 시간이 된다 |
| `BattleRules.KillTimeBonus` | number | R | 시간 추가가 성공할 때마다 늘어나는 초 |
| `Breaker.*` (12개 필드) | number | – | 브레이커 수치. 서버는 스탯 표(`start.upgrades`)로 충분하다 |
| `Enemies.Enemies[]` | array | R | 적 종류 목록(파일 순서가 의미를 가진다) |
| `Enemies.Enemies[].Type` | string(enum) | R | `Asteroid`, `Planet`, `Star`, `Comet` |
| `….MoveSpeed`, `….Radius`, `….RadiusStep` | number | – | 이동과 크기. 위치 관련이라 검증하지 않는다 |
| `….Tiers[]` | array | R | 색 등급 표(순서가 Tier 번호, 0부터). 공급되는 종류는 6개 |
| `….Tiers[].MaxHealth` | number | V | 기본 HP |
| `….Tiers[].Gold` | long | R | **기본 Gold** (크기 1). 장부의 입력 |
| `….Tiers[].Exp` | long | R | **기본 EXP** (크기 1). 장부의 입력 |
| `….Traits[]` | array | R | 붙을 수 있는 특수 성질 |
| `….Traits[].Type` | string(enum) | R | 성질 종류 |
| `….Traits[].MaxActive` | int | R | 동시 상한(0이면 없음) |
| `….Traits[].Effect` | object(10개 필드) | 일부 R | 사망 효과 수치. **황금**(`Golden`)만 `Multiplier`, `CritChance`, `CritRewardScale`을 읽는다. 나머지 성질과 나머지 필드는 읽지 않는다 |
| `….UpgradesTo` | string(enum) \| null | R | 변환 대상 종류 |
| `….SpawnPeriod` | number | R | 0보다 크면 픽업(혜성)이고 주기 출현 종류다 |
| `….RainCount` | int | R | 혜성 비 한 번에 나오는 수 |
| `Enemies.EnemyPlacement` | object | – | 일반 출현 띠 |
| `Enemies.PeriodicSpawnPlacement` | object | – | 주기 출현 띠 |
| `Enemies.StartSupply[]` | array | R | 시작 공급 목록(파일 순서) |
| `Enemies.StartSupply[].Enemy` | string(enum) | R | 종류. `null`이면 안 된다 |
| `Enemies.StartSupply[].Count` | int | R | 수량(1 이상) |
| `Growth.LevelExp[]` | long 배열 | R | `LevelExp[i]` = Level `i+1`에 닿는 누적 EXP |
| `Growth.Milestones[]` | array | R | 이정표(Level 오름차순) |
| `Growth.Milestones[].Level` | int | R | 이정표 Level |
| `Growth.Milestones[].TargetGold` | long | R | 목표 잔액(Gold) |
| `Growth.Milestones[].FieldScale` | number | – | 전장 배율. 화면용 |

### 4.3 예시

실제 에셋의 값을 일부 따왔다(`// …`는 같은 형식이 이어진다는 표시이고 파일에는 없다). 소행성은 6개 Tier가 모두 있고, 황금 성질은 `Effect`의 10개 필드가 모두 나온다(쓰지 않는 칸은 0).

```jsonc
{
  "FormatVersion": 1,
  "Content": {
    "BattleRules": { "TimeLimit": 12.0, "KillTimeBonus": 0.3 },
    "Breaker": {
      "Damage": 1.0, "Interval": 1.0, "Radius": 0.26, "CritChance": 0.0, "CritDamage": 1.0,
      "MoonDuration": 3.0, "MoonSpeedBonus": 0.1, "MoonRadiusBonus": 0.1,
      "CometDuration": 3.0, "CometCritDamageBonus": 0.5, "PlanetBonusDamage": 0.0, "StarBonusDamage": 0.0
    },
    "Enemies": {
      "Enemies": [
        {
          "Type": "Asteroid", "MoveSpeed": 1.5, "Radius": 0.312, "RadiusStep": 0.35,
          "Tiers": [
            { "MaxHealth": 3.0,  "Gold": 1,    "Exp": 1 },
            { "MaxHealth": 9.0,  "Gold": 6,    "Exp": 3 },
            { "MaxHealth": 18.0, "Gold": 36,   "Exp": 9 },
            { "MaxHealth": 27.0, "Gold": 216,  "Exp": 27 },
            { "MaxHealth": 36.0, "Gold": 1296, "Exp": 81 },
            { "MaxHealth": 46.0, "Gold": 7776, "Exp": 243 }
          ],
          "Traits": [
            {
              "Type": "Golden",
              "Effect": {
                "Multiplier": 50.0, "CritRewardScale": 1.0, "Damage": 0.0, "Radius": 0.0, "MaxTargets": 0,
                "BranchChance": 0.0, "CritChance": 0.0, "CritMultiplier": 0.0, "HealthFraction": 0.0, "Width": 0.0
              },
              "MaxActive": 0
            }
            // Electric 성질 …
          ],
          "UpgradesTo": "Planet", "SpawnPeriod": 0.0, "RainCount": 0
        },
        // Planet, Star …
        {
          "Type": "Comet", "MoveSpeed": -5.0, "Radius": 0.54, "RadiusStep": 0.0,
          "Tiers": [ { "MaxHealth": 1.0, "Gold": 0, "Exp": 0 } ],
          "Traits": [ { "Type": "Comet", "Effect": { /* 10개 필드, 모두 0 */ }, "MaxActive": 0 } ],
          "UpgradesTo": null, "SpawnPeriod": 5.0, "RainCount": 15
        }
      ],
      "EnemyPlacement": { "MinDistance": 2.0, "MaxDistance": 9.6 },
      "PeriodicSpawnPlacement": { "InnerOffset": -1.0, "OuterOffset": 0.5 },
      "StartSupply": [ { "Enemy": "Asteroid", "Count": 20 } ]
    },
    "Growth": {
      "LevelExp": [ 80, 350, 1200 /* … */ ],
      "Milestones": [
        { "Level": 10, "TargetGold": 690000, "FieldScale": 1.68 },
        { "Level": 20, "TargetGold": 5600000, "FieldScale": 2.27 },
        { "Level": 30, "TargetGold": 420000000, "FieldScale": 2.83 }
      ]
    }
  }
}
```

## 5. A1 계약: 클라이언트

### 5.1 패키지 의존

`ContentJson`은 Newtonsoft(`com.unity.nuget.newtonsoft-json` 3.2.2)를 쓴다. **이 패키지는 현재 `Packages/manifest.json`에 직접 들어 있지 않고 `com.unity.visualscripting`의 전이 의존으로만 설치되어 있다**(`packages-lock.json` depth 1). 다른 패키지가 빠지면 같이 사라질 수 있으므로, A1에서 `manifest.json`에 **명시적으로 추가**하고 버전을 고정한다(직렬화 결과가 해시에 들어가므로 버전이 바뀌면 안 된다).

### 5.2 `ContentJson` (에디터 어셈블리)

내보내기와 빌드 시 해시 계산이 **같은 코드**를 쓰도록 한 곳에 둔다. 런타임에서는 쓰지 않으므로 에디터 어셈블리(`Assets/Scripts/Editor/`)에 둔다. 그러면 `Core` 어셈블리(`noEngineReferences`)를 건드리지 않는다.

```csharp
public static class ContentJson
{
    // ContentData → 파일 내용(문자열). LF, 들여쓰기 2칸, 끝에 개행, FormatVersion 포함.
    public static string Serialize(ContentData data);

    // 파일 내용 → ContentData (왕복 테스트와 진단용).
    public static ContentData Deserialize(string json);

    // 문자열 → 번들 해시(3장의 알고리즘). 서버와 같은 결과여야 한다.
    public static string Version(string json);
}
```

- 직렬화 설정: `Formatting.Indented`(2칸), `StringEnumConverter`(이름), `NullValueHandling.Include`, `CultureInfo.InvariantCulture`, 줄바꿈 `\n`. `TypeNameHandling`은 쓰지 않는다.
- 래퍼 타입(`BattleContentFile { int FormatVersion; ContentData Content; }`)으로 감싸서 직렬화한다.

### 5.3 내보내기 메뉴 (`BlackHole > Export Battle Content`)

1. `GameContentSetup.ToData()`로 `ContentData`를 만들고 **`ContentLoader.Load`로 검증**한다. 오류가 있으면 파일을 쓰지 않고 진단을 보여 준다. 따라서 **내보낸 파일은 항상 클라이언트 로더를 통과한 데이터**다(서버가 가정해도 되는 보장).
2. `ContentJson.Serialize`로 문자열을 만들고 서버 저장소의 `content/battle/content.json`에 쓴다(서버 저장소 경로는 EditorPrefs). 기존 파일과 **내용이 같으면 쓰지 않는다**.
3. 끝나면 번들 해시와 파일 경로를 콘솔에 출력한다.

### 5.4 클라이언트 번들 해시 (A4')

클라이언트가 `battle` 엔트리에 싣는 `contentVersion`은 **현재 에셋의 상태**를 가리켜야 한다. 그래야 에셋을 고치고 내보내기를 빠뜨렸을 때 서버가 알아챈다(시나리오 S6). 그리고 런타임 직렬화는 쓰지 않는다(3장). 그래서:

- **빌드 시점:** 빌드 전처리기 (`IPreprocessBuildWithReport`)가 현재 에셋의 `ContentData`를 `ContentJson.Serialize`한 뒤 `ContentJson.Version`을 구해 **텍스트 파일 (`Assets/Resources/BattleContentVersion.txt`)** 에 쓴다. 이 파일은 생성물이라 `../../.gitignore`에 넣는다.
- **에디터 플레이 모드:** 플레이 모드에 들어가기 직전에 같은 계산을 해서 값을 넘긴다.
- **런타임 코드는 `ContentVersion.Current`(문자열) 하나만 읽는다.** 값의 출처(빌드 파일 또는 에디터)를 알 필요가 없다.
- 값을 구할 수 없으면(파일 없음) 전투 엔트리를 만들지 않고 오류를 로그로 남긴다(빈 해시를 서버에 보내지 않는다).

### 5.5 왕복 테스트 (에디터 EditMode)

1. `ContentData → Serialize → Deserialize`의 결과가 원본과 **필드 단위로 같다**(목록 순서 포함).
2. 연속 두 번 `Serialize`한 결과가 **바이트까지 같다**.
3. 서버 저장소에 내보낸 실제 파일이 `ContentLoader`를 통과한다.
4. 3장의 공유 테스트 벡터를 확인한다(`Version`이 `a4ed84f2186aeaca`, CRLF와 BOM 입력도 같은 값).

## 6. A2 계약: 서버

### 6.1 읽기 규칙

- **Jackson**(Spring Boot에 포함)으로 읽는다. `FAIL_ON_UNKNOWN_PROPERTIES = false`, `USE_BIG_DECIMAL_FOR_FLOATS = true`.
- enum은 **이름이 정확히 같을 때만** 매핑한다(대소문자 구분). 모르는 이름은 진단이다(조용히 `null`이 되지 않는다).
- 정수 필드에 소수가 오면 진단이다. `long` 범위를 넘는 값도 진단이다.
- 필수 필드가 없거나 `null`이면 진단이다(`UpgradesTo`만 `null` 허용). 서버가 읽지 않는 필드는 있어도 없어도 된다.
- 파일을 **바이트로 읽어** 해시를 계산한 뒤 같은 바이트를 파싱한다(해시와 파싱이 서로 다른 내용을 보지 않게).

### 6.2 객체 모델

패키지 `org.example.feedablackhole.battle.content`. Spring에 의존하지 않는 **순수 로직**이고, 시작 때 불러오는 연결만 `battle.config.BattleContentConfig`에 둔다(노드 콘텐츠의 `node.content`, `NodeContentConfig`와 같은 구조). 모든 객체는 **불변**이고 목록은 수정할 수 없다.

```java
public final class BattleContent {
    public String version();                         // 번들 해시(16자리)
    public BattleRules rules();
    public List<EnemyKind> kinds();                  // 파일 순서
    public Optional<EnemyKind> kind(EnemyType type);
    public List<SupplyEntry> startSupply();          // 파일 순서, 같은 종류가 여러 번 나올 수 있다
    public Growth growth();
}

public enum EnemyType {                              // JSON 이름 = C# enum 이름
    ASTEROID("Asteroid"), PLANET("Planet"), STAR("Star"), COMET("Comet");
    public String jsonName();
    public static Optional<EnemyType> fromJsonName(String name);
}
public enum TraitType { GOLDEN("Golden"), ELECTRIC("Electric"), MOON("Moon"),
                        LASER("Laser"), SUPERNOVA("Supernova"), COMET("Comet"); /* 동일한 jsonName, fromJsonName */ }

public record BattleRules(BigDecimal timeLimit, BigDecimal killTimeBonus) {}

public record EnemyKind(
        EnemyType type,
        List<Tier> tiers,                            // 인덱스가 Tier 번호(0부터)
        List<Trait> traits,
        Optional<EnemyType> upgradesTo,
        BigDecimal spawnPeriod,
        int rainCount) {
    public boolean isPickup();                       // spawnPeriod > 0
    public Tier tier(int index);                     // 범위를 벗어나면 IllegalArgumentException
    public Optional<Trait> trait(TraitType type);
    public Optional<GoldenEffect> golden();          // 황금 성질이 있으면 그 효과
}
public record Tier(BigDecimal maxHealth, long gold, long exp) {}
public record Trait(TraitType type, int maxActive, Optional<GoldenEffect> golden) {}
public record GoldenEffect(BigDecimal multiplier, BigDecimal critChance, BigDecimal critRewardScale) {}
public record SupplyEntry(EnemyType kind, int count) {}

public record Growth(List<Long> levelExp, List<Milestone> milestones) {
    public int maxLevel();                           // levelExp.size()
    public int maxStage();                           // milestones.size()
    public int startLevelAt(int stage);              // 성장도 0이면 0, 아니면 직전 이정표의 Level
    public Optional<Milestone> nextMilestoneAt(int stage);  // 마지막 이정표 뒤면 비어 있음
    public int goalLevelAt(int stage);               // 다음 이정표의 Level, 없으면 0
    public OptionalLong expToReach(int level);       // Level 0 → 0, 사다리 밖 → 비어 있음
    public int levelFor(long exp, int stage);        // 아래 설명
}
public record Milestone(int level, long targetGold) {
    public long rewardFor(long gold);                // max(0, targetGold − gold)
}
```

- 서버가 읽지 않는 필드(`MoveSpeed`, `Radius`, `RadiusStep`, 황금 외 사망 효과, `Breaker`, 출현 띠, `FieldScale`)는 객체에 **담지 않는다.**
- 성장도 인자(`stage`)가 `0 … maxStage()` 밖이면 `IllegalArgumentException`이다(클라이언트 `HqGrowthDefinition.RequireStage`와 같다).

**`levelFor(exp, stage)` 의미** (클라이언트 `Hq`의 계산과 같다): 시작 Level = `startLevelAt(stage)`, 목표 Level = `goalLevelAt(stage)`. `exp`가 `expToReach(Level+1)` 이상인 동안 Level을 올리되 **목표 Level에 닿으면 멈춘다**(이정표에 닿은 판은 그 Step에서 끝나고 Level은 목표를 넘지 않는다). 목표가 없으면 사다리 끝까지 올린다.

### 6.3 검증 규칙

클라이언트 `ContentLoader`가 보는 규칙 중 **서버가 읽는 데이터에 걸린 것**을 같은 기준으로 본다. 진단은 **경로와 함께 모두 모아서** 보고하고, 하나라도 있으면 서버를 시작하지 않는다. 경로는 JSON 경로 그대로(`Content.Enemies.Enemies[Asteroid].Tiers[2]`)다.

**두 단계로 본다**(앞 단계에 오류가 있으면 뒤 단계를 보지 않는다. 잘못된 정의가 거짓 참조 오류를 만들지 않게): 1단계 개별 값과 모양, 2단계 정의끼리의 규칙.

| 단계 | 경로 | 규칙 | 클라이언트 근거 |
|---|---|---|---|
| 1 | `FormatVersion` | 정수 1 | (서버 규칙) |
| 1 | `Content`, `Content.BattleRules`, `Content.Enemies`, `Content.Enemies.Enemies`, `Content.Growth` | 존재(`null` 아님) | `ContentLoader` |
| 1 | `….BattleRules.TimeLimit` | 유한한 양수 | `TimeLimitDefinition` |
| 1 | `….BattleRules.KillTimeBonus` | 유한한 0 이상 | `TimeLimitDefinition` |
| 1 | `….Enemies[i].Type` | 알려진 이름 | `EnemyDefinition` |
| 1 | `….Enemies[type].SpawnPeriod` | 유한한 0 이상 | `EnemyDefinition` |
| 1 | `….Enemies[type].RainCount` | 0 이상, 0보다 크면 `SpawnPeriod > 0` | `EnemyDefinition` |
| 1 | `….Enemies[type].Tiers` | 하나 이상 | `EnemyDefinition` |
| 1 | `….Tiers[j].MaxHealth` | 유한한 양수 | `EnemyTier` |
| 1 | `….Tiers[j].Gold`, `.Exp` | 0 이상 | `EnemyTier` |
| 1 | `….Traits[k].Type` | 알려진 이름, 한 종류 안에서 중복 없음 | `EnemyDefinition`, `EnemyTraitDefinition` |
| 1 | `….Traits[k].MaxActive` | 0 이상 | `EnemyTraitDefinition` |
| 1 | `….Traits[k].Effect` | 존재 | `EnemyContentLoader` |
| 1 | 황금 `Effect.Multiplier` | 유한한 양수 | `GoldenDefinition` |
| 1 | 황금 `Effect.CritChance` | 0 이상 1 이하 | `GoldenDefinition` |
| 1 | 황금 `Effect.CritRewardScale` | 유한한 양수 | `GoldenDefinition` |
| 1 | `Content.Enemies.StartSupply[i].Enemy` | `null` 아님 | `EnemyContentLoader` |
| 1 | `….StartSupply[i].Count` | 1 이상 | `SupplyRequest` |
| 1 | `Content.Growth.LevelExp` | 모두 양수이고 **순증가** | `HqGrowthDefinition` |
| 1 | `Content.Growth.Milestones[i]` | `Level ≥ 1`, `TargetGold ≥ 0` | `HqMilestone` |
| 2 | `….Enemies` | 종류 유일 | `EnemyContentInvariants.CollectEnemies` |
| 2 | `….Enemies[type].Tiers` | 공급되는 종류(`SpawnPeriod == 0`)는 **6개** | `CheckTierCounts` |
| 2 | `….Enemies[type]` 픽업 | 성질이 **정확히 하나**, `UpgradesTo` 없음 | `EnemyDefinition` |
| 2 | `….Enemies[type].UpgradesTo` | 목록에 있고, 자기 자신이 아니고, 픽업이 아님 | `CheckKindLinks` |
| 2 | `….Enemies[type].UpgradesTo` | 변환 사슬이 돌지 않음 | `CheckKindLinks` |
| 2 | `….StartSupply[i].Enemy` | 목록에 있고 픽업이 아님 | `LoadSupplyList`, `CheckSupplyKinds` |
| 2 | `Content.Growth.Milestones` | `Level`이 `maxLevel()` 이하이고 **순증가**, `TargetGold`도 **순증가** | `HqGrowthDefinition` |

`Breaker`, 출현 띠, `FieldScale`, 황금 외 사망 효과의 규칙은 서버가 읽지 않으므로 보지 않는다(클라이언트가 내보내기 전에 이미 검증했다).

### 6.4 시작 동작

- 시작할 때 한 번 읽고 검증해 `BattleContent`를 만든다. 요청을 처리하는 동안 파일을 읽지 않는다.
- 진단이 하나라도 있으면 **모든 진단을 한 번에 로그로 남기고 서버를 시작하지 않는다**(`ContentLoadException`).
- 성공하면 **번들 해시, 종류 수, 시작 공급 합계, 성장 단계 수**를 INFO로 한 줄 남긴다.
- 스프링 빈 하나(`BattleContent`)로 노출한다. 다른 작업(C2~C4, C3)은 이 빈을 주입받는다.

## 7. 소비자 계약: 어떤 작업이 무엇을 쓰나

| 작업 | `BattleContent`에서 쓰는 것 | 클라이언트 근거 |
|---|---|---|
| **C4a** 공급 계산 | `startSupply()`, `kinds()`의 순서와 `upgradesTo`(변환 사슬의 위상 정렬은 **종류 순서를 지킨다**), `isPickup()` | `GameSessionFactory.StartSupplyOf/ChainOrder/GrowthSupplyOf` |
| **C4b** 허용 범위 | `kind.tiers().size()`(색 수), `kind.golden()`, `kind.trait(...)`의 `maxActive` | `EnemyComposition.From`, `MassRule` |
| **C3** L1 검증 | `rules()`(제한 시간, 시간 추가 초), `kind.tier(t).gold()/exp()`, `kind.golden()`, `growth()`(`levelFor`, `startLevelAt`, `goalLevelAt`, `nextMilestoneAt`, `rewardFor`), `kind.spawnPeriod()`/`rainCount()`(혜성 상한) | `Hq`, `HqMilestone`, `DeathRewards`, `EnemyDefinition.StatsAt` |
| **C2** 스탯 표 | **쓰지 않는다**(노드 콘텐츠만 쓴다) | |

## 8. 테스트 계약

**서버(A2)**

1. 실제 `content.json`이 로드되고 요약이 맞다(현재 에셋 기준: 종류 4개, 공급되는 종류는 Tier 6개, 혜성은 `SpawnPeriod` > 0, 시작 공급과 이정표 수). 콘텐츠를 바꾸면 의도적으로 갱신하는 스냅샷 테스트다(`RealBattleContentTests`).
2. 3장의 **공유 테스트 벡터**가 맞고, CRLF와 BOM 입력이 같은 값을 낸다. 한 글자를 바꾸면 값이 달라진다.
3. 6.3의 규칙마다 **그 규칙만 어긴 JSON** 하나씩이 해당 경로의 진단을 낸다(여러 오류가 있으면 모두 모아서 낸다). 1단계 오류가 있으면 2단계 진단이 나오지 않는다.
4. `Growth`의 질의 메서드: `startLevelAt`, `goalLevelAt`, `expToReach`, `levelFor`(목표 Level에서 멈춤, 목표가 없는 성장도, 사다리 끝), `rewardFor`(잔액이 목표보다 많을 때 0), 성장도 범위 밖.
5. enum 이름 매핑: 모르는 이름, 대소문자가 다른 이름이 진단이다.

**클라이언트(A1, A4')**: 5.5절.

**교차 확인 (양쪽이 같은 파일을 본다):** 클라이언트가 내보낸 파일을 서버 저장소에 커밋하면 서버의 `RealBattleContentTests`가 그 파일을 읽는다. 클라이언트가 파일을 바꾸면 서버 테스트가 바로 그 변경을 보고, 서버 테스트가 실패하면 내보낸 파일이 계약을 어겼다는 뜻이다.

## 9. 확정할 결정

| # | 결정 | 권장 |
|---|---|---|
| 1 | 최상위에 `FormatVersion` 래퍼를 둘지 | **둔다.** 작업 문서의 "DTO 그대로"에서 한 단계 감싸는 것이지만, 스키마 변경을 서버가 조용히 놓치지 않게 한다 |
| 2 | 해시 대상 | **파일 전체.** 서버가 쓰지 않는 필드를 바꿔도 서버 재배포가 필요해진다는 비용이 있다(수용). 서버가 쓰는 부분만 해시하려면 양쪽이 같은 부분집합 직렬화를 구현해야 해서 복잡하다 |
| 3 | `ContentJson`의 위치 | **에디터 어셈블리.** 런타임이 직렬화하지 않으므로 `Core`를 건드리지 않는다 |
| 4 | 클라이언트 해시의 출처 | **빌드 시점에 에디터가 계산해 빌드에 포함**(5.4). 런타임 직렬화는 쓰지 않는다 |
| 5 | 서버가 읽는 소수의 타입 | `BigDecimal`. 클라이언트의 float와의 차이는 C3의 허용 오차가 흡수한다 |
| 6 | enum 이름 | C# enum 이름 그대로(PascalCase). `battle` 엔트리의 `kind`도 같은 이름을 쓴다(`EnemyType.fromJsonName`) |

**알려진 한계**

- 서버가 읽지 않는 필드를 클라이언트가 바꿔도 서버가 알 수 없다. 해시가 달라져서 재배포를 강제할 뿐이다.
- 에디터 플레이 모드에서 에셋을 바꾸고 내보내지 않은 채 서버와 붙으면 `CONTENT_VERSION_MISMATCH`가 난다(의도된 동작).
- 빌드 시 해시 파일이 없는 환경(예: 에디터를 거치지 않은 빌드)에서는 전투 엔트리가 만들어지지 않는다.

## 10. 작업 문서와 달라지는 점

이 문서를 확정하면 [offline-sync-work-breakdown.md](offline-sync-work-breakdown.md)의 아래 부분을 맞춰 고친다.

| 작업 | 작업 문서의 기술 | 이 계약의 기술 |
|---|---|---|
| A1 | "직렬화는 프로젝트에 이미 있는 Newtonsoft", `ContentJson`은 런타임 어셈블리에서도 쓸 수 있게 | Newtonsoft는 전이 의존이라 **`manifest.json`에 명시 추가**. `ContentJson`은 **에디터 어셈블리** |
| A1 | 루트는 `ContentData` | 루트는 `{ FormatVersion, Content }` 래퍼 |
| A4' | 앱 시작 때 런타임에 직렬화해 해시를 계산 | **빌드 시점에 에디터가 계산**해 빌드에 포함하고 런타임은 읽기만. 규모는 0.5일에서 약 1일로 늘어난다(전처리기, 에디터 플레이 모드 처리, 생성 파일) |
| A2 | 서버 객체와 검증 규칙을 개략적으로 | 6장의 모델과 규칙, 시작 동작, 소비자 계약 |
