# 서버 연동 계약 (A0)

- 상태: **확정** (2026-10-12). 1장(콘텐츠), 2장(클라이언트 인증) 확정, 3장(로그·동기화 JSON), 4장(서버 스키마·처리기), 5장(클라이언트 로그와 동기화) 확정.
- 이 문서는 [server-sync-plan.md](server-sync-plan.md)(계획서)의 설계를 **구현 가능한 형식과 시그니처**로 확정한다. 담당 간 경계는 이 문서가 기준이며, 변경은 A만 한다([작업지시](server-sync-assignment.md) 1장).

| 장 | 내용 | 작업지시 ID | 상태 |
|---|---|---|---|
| 1 | 콘텐츠 묶음 형식, 서버 콘텐츠 객체, 전투 규칙 인터페이스 | A0-2, A0-3 | 확정 |
| 2 | 클라이언트 인증 경계, 타임아웃 | A0-6 일부, A0-7 일부 | 확정 |
| 3 | 로그·동기화 JSON | A0-1 | 확정 |
| 4 | 서버 스키마, 처리기 인터페이스 | A0-4, A0-5 | 확정 |
| 5 | 클라이언트 로그·저장 경계, 나머지 수치 | A0-6, A0-7 | 확정 |

---

## 1. 콘텐츠

### 1.1 원칙

- 콘텐츠의 원본은 **클라이언트 저장소**다. 서버는 클라이언트 에디터 도구(W0-1)가 내보낸 파일을 그대로 커밋해 쓴다. 서버 저장소에서 콘텐츠 파일을 손으로 고치지 않는다.
- 서버는 **검증에 필요한 값만** 가진다(계획서 4.2). HP, 이동 속도, 반지름, 배치, 블랙홀 공격 수치, 황금 외 사망 효과는 내보내지 않는다.
- **노드 → 스탯 → 적 구성**으로 이어지는 연결(어떤 statId가 어떤 적의 어떤 값에 닿는가)은 클라이언트 코드(`EnemyUpgradeStats`, `TimeLimitDefinition`, `HqUpgradeStats`)에 있다. 이 연결을 Java로 다시 옮기지 않고 **내보내기 도구가 데이터로 내보낸다**(1.4의 `stats`). 서버가 옮기는 것은 계산식뿐이다(1.6).

### 1.2 묶음 구성

서버 저장소 `src/main/resources/content/` 아래에 둔다.

```
content/
├─ manifest.json
├─ nodes/
│  ├─ UpgradeStats.csv
│  ├─ Nodes.csv
│  ├─ NodeCost.csv
│  ├─ NodeEffects.csv
│  └─ layout.json
└─ battle/
   └─ battle.json
```

- `nodes/`는 **현재 서버 형식을 그대로 유지**한다(`NodeContentCsvReader`, `NodeLayoutReader`가 읽는 형식). 클라이언트 시트와 다른 점(열 이름 변경, 분석용 열 삭제)은 `tools/import_node_content.py`가 하던 변환을 내보내기 도구가 대신한다.

| 서버 파일 | 클라이언트 원본 | 변환 |
|---|---|---|
| `UpgradeStats.csv` | `Assets/Data/NodeTable/UpgradeStats.csv` | `StatId, ValueType, Unit, DefaultValue, Aggregation, Min, Max, Memo`만 남긴다(`Enabled`와 분석용 열 삭제) |
| `Nodes.csv` | `Nodes.csv` | `Rank 수` → `RankCount` |
| `NodeCost.csv` | `NodeCost.csv` | 빈 `Memo` 열 추가 |
| `NodeEffects.csv` | `NodeEffects.csv` | `단위` → `Unit`, `표시` 삭제, 빈 `Memo` 열 추가 |
| `layout.json` | `Assets/Data/NodeCatalog.asset`의 `_layout` | `{"Nodes":[{"Id","Start","X","Y","Links"}]}` (현재 서버 파일과 같은 형식) |
| `battle/battle.json` | `GameContentSetup.asset`이 가리키는 에셋들 | 1.4 |

- 파일 인코딩은 UTF-8(BOM 없음), 줄바꿈은 LF, 마지막 줄 끝에 LF 하나.

### 1.3 `manifest.json`과 콘텐츠 버전

```json
{
  "formatVersion": 1,
  "contentVersion": "3f9a0c1b7d2e4a58",
  "files": [
    "nodes/UpgradeStats.csv",
    "nodes/Nodes.csv",
    "nodes/NodeCost.csv",
    "nodes/NodeEffects.csv",
    "nodes/layout.json",
    "battle/battle.json"
  ]
}
```

**콘텐츠 버전 계산** (C#과 Java가 똑같이 구현한다):

1. `files` 순서대로 각 파일에 대해 `경로(UTF-8) + 0x00 + 파일 바이트 + 0x00`을 이어 붙인다. 경로는 `content/` 기준 상대 경로, 구분자는 `/`.
2. 이어 붙인 바이트의 SHA-256을 구한다.
3. 소문자 16진수의 **앞 16자**가 `contentVersion`이다.

- 파일 바이트를 그대로 해시하므로, 내보내기 도구가 쓴 파일과 서버가 읽는 파일이 같으면 버전도 같다. 파싱 결과를 정규화해 해시하지 않는다(언어 간 정규화 차이를 없애기 위해서다).
- **서버**는 시작할 때 버전을 다시 계산해 `manifest.json`의 값과 비교한다. 다르면(손으로 고친 경우) 시작하지 않는다.
- **클라이언트**는 콘텐츠를 불러올 때(실행 중) 내보내기 도구와 같은 루틴으로 묶음을 메모리에서 만들어 버전을 계산한다(W0-2, `LoadedContent.ServerContentVersion`). 그래서 내보내기를 잊어도 클라이언트 버전은 실제 콘텐츠를 따르고, 서버 버전과 달라지는 것으로 드러난다.
- 서버의 기존 `nodeContentVersion`(파싱 결과의 정규형 해시)은 이 `contentVersion`으로 대체했다(W0-3).

### 1.4 `battle.json`

```json
{
  "rules": { "timeLimitSeconds": 12, "killTimeBonusSeconds": 0.3 },
  "growth": {
    "levelExp": [10, 25, 45],
    "milestones": [
      { "level": 10, "targetGold": 690000 },
      { "level": 20, "targetGold": 5600000 },
      { "level": 30, "targetGold": 420000000 }
    ]
  },
  "stats": { "timer": "timer", "growthTime": "growth.time" },
  "enemies": [
    {
      "enemyId": "asteroid",
      "pickup": false,
      "upgradesTo": "planet",
      "tiers": [ { "gold": 1, "exp": 1 }, { "gold": 6, "exp": 2 } ],
      "traits": [ "golden", "electric" ],
      "golden": { "multiplier": 50, "critChance": 0.1, "critRewardScale": 1 },
      "stats": {
        "mass": "asteroid.massScale",
        "size": "asteroid.size",
        "startSupply": "asteroid.count",
        "growthSupply": "growth.asteroids",
        "upgrade": "asteroid.toPlanet",
        "timeChance": "asteroid.timeChance",
        "traitChance": { "golden": "goldenAsteroid.spawnChance", "electric": "electricAsteroid.spawnChance" },
        "goldenMultiplier": "goldenAsteroid.rewardScale",
        "goldenCritChance": "goldenAsteroid.critChance",
        "goldenCritRewardScale": "goldenAsteroid.critRewardScale"
      }
    }
  ],
  "startSupply": [ { "enemyId": "asteroid", "count": 20 } ]
}
```

위 값은 형식을 보여 주기 위한 예시다. 실제 값과 statId는 내보내기 도구가 채운다.

| 필드 | 원본 | 설명 |
|---|---|---|
| `rules.timeLimitSeconds` | `BattleRulesData.TimeLimit` | 기본 제한 시간(초) |
| `rules.killTimeBonusSeconds` | `BattleRulesData.KillTimeBonus` | 시간 추가 1회의 초 |
| `growth.levelExp` | `HqGrowthData.LevelExp` | `levelExp[i]`는 Level i+1에 닿는 누적 EXP. Level 0은 EXP 0 |
| `growth.milestones[].level` / `.targetGold` | `HqMilestoneData` | `fieldScale`은 내보내지 않는다 |
| `stats.timer` / `stats.growthTime` | `UpgradeStat.Timer`, `UpgradeStat.GrowthTime`의 statId | 제한 시간, Level업당 시간에 닿는 수치 |
| `enemies[]` | `EnemyData` | `EnemyCatalog`의 순서 그대로(이 순서가 처음 나온 종류 순서와 변환 사슬 정렬의 기준이다) |
| `enemyId` | `EnemyType` | 소문자: `asteroid`, `planet`, `star`, `comet`, `galaxy` |
| `pickup` | `SpawnPeriod > 0` | 수거형(혜성) 여부 |
| `upgradesTo` | `EnemyData.UpgradesTo` | 변환 대상 `enemyId`, 없으면 `null` |
| `tiers[]` | `EnemyTierData.Gold`, `.Exp` | 크기 1의 색 등급별 Gold·EXP. 순서가 tier 번호(0부터) |
| `traits[]` | `EnemyTraitData.Type` | 소문자 성질 id: `golden`, `electric`, `moon`, `laser`, `supernova`, `comet`, `gravity`. 종류의 성질 순서 그대로 |
| `golden` | `traits`에 `golden`이 있을 때만. `DeathEffectData`의 `Multiplier`, `CritChance`, `CritRewardScale` | 노드 반영 전 기본값. 없으면 `null` |
| `enemies[].stats.*` | `EnemyUpgradeStats`의 각 함수가 이 종류에 돌려주는 `UpgradeStat`의 statId | 해당 수치가 없으면 `null`. `traitChance`는 성질 id → statId(없는 성질은 키를 넣지 않는다) |
| `startSupply[]` | `EnemyContentData.StartSupply` | 콘텐츠 순서 그대로, 같은 종류가 여러 번 나올 수 있다 |

- **숫자 표기**: 정수(`long`, `int`)는 정수로 쓴다. `float` 값은 **float의 가장 짧은 왕복 표기**로 쓴다(.NET의 `float.ToString("R", InvariantCulture)`). 서버는 `Float.parseFloat`로 읽어 같은 float을 얻는다.
- 키 순서와 공백은 내보내기 도구가 고정한다(버전이 파일 바이트에 의존하므로, 같은 콘텐츠는 항상 같은 바이트여야 한다). 들여쓰기 2칸, 키 순서는 위 예시 순서.

### 1.5 서버 콘텐츠 객체 (Java)

패키지 `org.example.feedablackhole.battle.content`. 로더(W0-3, B)가 `battle.json`을 읽어 아래 불변 객체를 만든다. 검증 실패 시 서버를 시작하지 않는다(`NodeContentConfig`와 같은 방식).

```java
public record BattleContent(
        BattleRulesDefinition rules,
        HqGrowthDefinition growth,
        GlobalStatIds stats,
        List<EnemyKindDefinition> enemies,      // battle.json 순서
        List<SupplyEntry> startSupply) {
    public EnemyKindDefinition enemy(String enemyId);   // 없으면 예외
    public Optional<EnemyKindDefinition> findEnemy(String enemyId);
}

public record BattleRulesDefinition(float timeLimitSeconds, float killTimeBonusSeconds) {}

public record HqGrowthDefinition(List<Long> levelExp, List<HqMilestone> milestones) {
    public int maxStage();                      // milestones.size()
    public int startLevelAt(int stage);         // stage 0 → 0, 아니면 milestones[stage-1].level
    public int goalLevelAt(int stage);          // 다음 이정표 Level, 없으면 0
    public Optional<HqMilestone> nextMilestoneAt(int stage);
    public OptionalLong expToReach(int level);  // Level 0 → 0, 사다리 밖 → empty
}

public record HqMilestone(int level, long targetGold) {
    public long rewardFor(long currentGold);    // max(0, targetGold - currentGold)
}

public record GlobalStatIds(String timer, String growthTime) {}

public record EnemyKindDefinition(
        String enemyId,
        boolean pickup,
        String upgradesTo,                      // nullable
        List<EnemyTier> tiers,
        List<String> traits,
        GoldenTrait golden,                     // nullable
        EnemyStatIds stats) {}

public record EnemyTier(long gold, long exp) {}

public record GoldenTrait(float multiplier, float critChance, float critRewardScale) {}

public record EnemyStatIds(
        String mass, String size, String startSupply, String growthSupply,
        String upgrade, String timeChance,
        Map<String, String> traitChance,        // 성질 id → statId
        String goldenMultiplier, String goldenCritChance, String goldenCritRewardScale) {}  // 각각 nullable

public record SupplyEntry(String enemyId, int count) {}
```

**로더 검증** (클라이언트 로더와 같은 규칙만):

- `enemyId` 유일, `upgradesTo`가 존재하는 종류를 가리키고 변환 사슬에 순환이 없음.
- 공급 종류(`pickup == false`)는 tier 6개, 수거형은 1개. Gold·EXP는 0 이상.
- `golden`은 `traits`에 `golden`이 있을 때만 있고, 있으면 반드시 있다.
- 모든 `stats` statId(`null` 제외)가 `UpgradeStats.csv`에 있다.
- `levelExp`는 양수이고 증가한다. 이정표 Level은 증가하고 사다리 안이며, `targetGold`도 증가한다.
- `startSupply`의 종류가 존재하고 수거형이 아니며 `count`가 0 이상이다.

### 1.6 전투 규칙 인터페이스 (Java)

패키지 `org.example.feedablackhole.battle.rules`. B가 구현(W1-4)하고, A의 결산 검증(W1-6)과 B의 판 시작 처리기(W1-5)가 쓴다.

```java
public interface BattleRules {

    /** 산 노드 Rank(nodeId → rank)로 스탯 값을 계산한다. 클라이언트 NodePurchase.StatsFor. */
    UpgradeValues upgradesFor(Map<String, Integer> nodeRanks);

    /** 성장도와 스탯 값으로 이번 판의 설정을 계산한다. 클라이언트 GameSessionFactory.Create 중 검증에 필요한 부분. */
    RunSetup setupFor(int growthStage, UpgradeValues upgrades);
}

public interface UpgradeValues {
    float valueOf(String statId);   // clamp(default + sum)
    float gainOf(String statId);    // valueOf(statId) - default
}

public interface RunSetup {
    int growthStage();
    int startLevel();
    int goalLevel();                            // 0이면 목표 없음(마지막 이정표 뒤)
    long startExp();                            // startLevel에 닿는 누적 EXP
    Optional<HqMilestone> nextMilestone();

    float timeLimitSeconds();                   // 기본 + timer 증가분
    float growthTimeSeconds();                  // Level업 1회당 더하는 초
    float killTimeBonusSeconds();

    List<SupplyEntry> startSupply();            // 변환 반영, 처음 나온 종류 순서, 0마리 종류 제외

    KindSetup kind(String enemyId);             // 이번 판의 종류별 구성. 없는 종류면 예외
    Optional<KindSetup> findKind(String enemyId);

    /** 이번 판의 EXP와 Level 표로 도달 Level을 계산한다. goalLevel을 넘지 않는다. Hq.RaiseLevels. */
    int levelForExp(long exp);
}

public interface KindSetup {
    String enemyId();
    boolean pickup();
    int maxSize();                              // 1 ~ 20
    boolean tierPossible(int tier);             // 이번 판 질량의 색 비율이 0보다 큰가(MassRule)
    boolean goldenPossible();                   // golden 성질이 있고 생성 확률 > 0
    float timeChance();                         // 0 ~ 1
    Optional<GoldenTrait> golden();             // 노드가 반영된 황금 수치

    long goldOf(int tier, int size, boolean golden);    // EnemyDefinition.StatsAt의 Gold
    long expOf(int tier, int size);                     // EnemyDefinition.StatsAt의 Exp
    long goldenCritBonusOf(int tier, int size);         // round(goldOf(tier, size, true) × critRewardScale)
}
```

- 범위를 벗어난 입력(없는 tier, `maxSize`보다 큰 size, 황금이 불가능한데 `golden = true`)은 `IllegalArgumentException`이다. 결산 검증은 먼저 `tierPossible`·`maxSize`·`goldenPossible`로 S2를 확인한 뒤 값을 묻는다.
- `tierPossible`을 위해 질량 규칙(`MassRule`, 약 30줄)도 옮긴다. 계획서 S2에 "이번 판 질량에서 나올 수 없는 색" 검사를 더한다.

### 1.7 수치 일치 규칙

서버 계산은 **클라이언트와 같은 타입과 순서**로 한다. 정확한 등식 검사(S3, S6, S9)가 1의 차이로도 실패하기 때문이다.

| 계산 | 클라이언트 | 서버 구현 규칙 |
|---|---|---|
| 효과 값 | CSV 문자열 → `float` | `BigDecimal.floatValue()` (IEEE 최근접 반올림, C#과 같은 float) |
| 스탯 합 | `double` 누적. 배치된 노드만, **배치 순서**(`NodeTree.Nodes` = `layout.json`의 `Nodes` 순서) → Rank 1부터 → 같은 Rank 안에서는 수치 순 → 값 순으로 정렬된 효과 순서 | 같은 순서로 `double` 누적. 서버 `NodeTree.nodes()`(배치 순서)와 `NodeRankDefinition.effects()`(수치 시트 순 → 값 순으로 이미 정렬됨)를 그대로 순회하면 된다. 수치마다 따로 더하므로 순서가 결과에 닿는 것은 같은 수치에 더해지는 값들뿐이다 |
| `valueOf` | `(float) min(max, max(min, (double) default + sum))` | 같음. 빈 min·max는 무한대 |
| `gainOf` | `valueOf − default` (float 뺄셈) | 같음 |
| 정수화(`Whole`) | `Math.Round(float, AwayFromZero)` | .5에서 0에서 먼 쪽으로 반올림 |
| 확률(`Percent`) | `min(1, gain / 100)` (float) | 같음 |
| 황금 배율 | `golden.Multiplier + gain / 100` (float) | 같음. 치명 확률은 `min(1, …)` |
| Gold·EXP | `checked((long) Math.Round(value × (double) multiplier, AwayFromZero))` | `long × (double) float`를 .5에서 0에서 먼 쪽으로 반올림. 크기 배율 `1 + (size − 1) × 1.0f` |
| 제한 시간 | `Duration + gainOf(timer)` (float) | 같음 |
| 성장 공급·시작 공급 | `GameSessionFactory.StartSupplyOf` | 같은 순서(콘텐츠 시작 공급 → 보너스 → 변환 사슬 위상 정렬) |

- 일치 여부는 C#이 생성한 기대값 표(W4-1a)로 확인한다. 이 표가 이 장의 실행 가능한 기준이다.

### 1.8 결정 사항 (1장, 확정)

| # | 결정 | 이유 |
|---|---|---|
| C1 | 노드 파일은 현재 서버 형식을 유지하고, 전투 데이터만 `battle.json`을 새로 둔다 | 서버 노드 로더를 바꾸지 않아도 된다 |
| C2 | 콘텐츠 버전은 **파일 바이트**의 해시(SHA-256 앞 16자) | 언어 간 정규화 차이가 없다 |
| C3 | 스탯 → 적 구성의 연결은 **데이터로 내보낸다**(`stats` 필드) | `EnemyUpgradeStats`의 switch를 Java로 옮기지 않아도 되고, 클라이언트가 바꾸면 자동으로 따라간다 |
| C4 | 질량 규칙을 옮겨 S2에 "나올 수 없는 색" 검사를 더한다 | 30줄 정도로 검증이 강해진다 |
| C5 | 서버 전투 계산은 BigDecimal이 아니라 C#과 같은 float·double로 한다 | 정확한 등식 검사를 위해서다. 노드 비용(정수)은 기존 BigDecimal 모델을 그대로 쓴다 |

### 1.9 산출물

| 산출물 | 위치 | 쓰는 작업 |
|---|---|---|
| 묶음 목차, 버전 계산(기준 구현) | `content/ContentManifest`, `content/ContentVersion` | W0-3(B), W0-1·W0-2(A·C가 C#으로 같은 계산을 구현) |
| 전투 콘텐츠 객체(1.5) | `battle/content/*` | W0-3, W1-4(B), W1-6(A) |
| 전투 규칙 인터페이스(1.6) | `battle/rules/*` (구현 없음) | W1-4(B 구현), W1-5(B), W1-6(A) |
| 샘플 묶음 | `src/test/resources/content-sample/` | W0-3 로더 테스트(B) |
| 테스트 | `ContentVersionTests`(샘플 묶음의 목차 버전과 Java 계산이 같은지), `HqGrowthDefinitionTests` | - |

**샘플 묶음** (`contentVersion` = `4d6c398ad1f5e49c`):

- `nodes/`: 2026-10-12 클라이언트 `dev`의 노드 시트와 `NodeCatalog.asset`을 `tools/import_node_content.py --out`으로 변환한 것(배치 노드 276개, 수치 71개). (당시 서버의 노드 파일은 노드 247개, 수치 60개였다. W0-3에서 실제 묶음으로 교체했다.)
- `battle/battle.json`: 같은 시점의 클라이언트 에셋(`BattleRules`, `HqGrowthSetup`, `EnemyCatalog`과 적 에셋 5개, `EnemySupplySetup`) 값. `stats`는 `EnemyUpgradeStats`를 손으로 옮겼다.
- 목차의 버전은 1.3의 계산법을 Python으로 따로 구현해 만들었고, Java 구현(`ContentVersion`)이 같은 값을 내는 것을 `ContentVersionTests`가 확인한다.
- 실제 묶음은 W0-1(내보내기 도구)이 만든다. 그때 이 샘플과 구조가 같아야 하고(W0-1 완료 조건), 값은 그 시점 콘텐츠를 따른다.

---

## 2. 클라이언트 인증

서버의 게스트 인증 API(등록, 로그인, 갱신)는 이미 구현되어 있다([API 명세](api-spec.md#인증)). 이 장은 **클라이언트가 그 API를 어떻게 쓰는지**를 정한다. 구현은 W2-1(통신 계층), W2-2(게스트 인증), W2-3(연결 상태와 타이틀).

### 2.1 원칙

- 로그인은 앱 시작 시 **백그라운드에서** 사용자 조작 없이 한다. 게임은 로그인 결과를 기다리지 않고 타이틀까지 뜬다.
- 로그인은 **앱 시작 시 한 번만** 시도한다(계획서 D9). 실패하면 그 실행이 끝날 때까지 오프라인이다.
- 로그인에 실패해도 게임은 그대로 할 수 있다. 서버 연결 여부는 진행에 영향을 주지 않고, 기록을 언제 보내느냐만 바꾼다.
- **서버가 거부한 것(4xx)과 서버에 닿지 않은 것(네트워크 오류, 타임아웃, 5xx)을 구분한다.** 닿지 않은 것은 언제나 "이번 실행은 오프라인"이고, 저장된 계정 정보를 건드리지 않는다.

### 2.2 어셈블리 구성

통계 전송(`BlackHole.Analytics` / `BlackHole.Analytics.Transport`)과 같은 방식으로, Unity에 의존하지 않는 부분과 의존하는 부분을 나눈다.

| 어셈블리 | Unity 의존 | 내용 |
|---|---|---|
| `BlackHole.Net` | 없음(`noEngineReferences: true`) | 요청·응답 타입, 인터페이스(2.4), 인증 흐름 구현, DTO. **흐름의 단위 테스트는 여기를 대상으로 한다.** |
| `BlackHole.Net.Unity` | 있음 | `UnityWebRequest` 전송 구현, 계정 정보 파일 저장, 서버 설정 에셋 |
| `BlackHole.Net.Tests` | Editor 테스트 | 가짜 전송·가짜 저장소로 흐름 테스트 |

- 연결 코드(`GameBootstrap`, 타이틀 화면)는 기존처럼 Assembly-CSharp에 둔다.
- 비동기는 `async`/`await`를 Unity 메인 스레드에서 쓴다(`AnalyticsClient`와 같은 방식). 스레드 풀로 넘기지 않는다.

### 2.3 서버 설정 에셋

`ServerSettings` (ScriptableObject, `Assets/Data/ServerSettings.asset`, `GameBootstrap`에 연결).

| 필드 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `true` | 끄면 항상 오프라인(로그인 시도 없음) |
| `baseUrl` | `http://localhost:8080` | 경로 없이. 통계 설정(`AnalyticsSettings`)과 같은 서버지만 설정은 따로 둔다 |
| `requestTimeoutSeconds` | `5` | 인증 요청 하나의 타임아웃 |
| `loginBudgetSeconds` | `8` | 로그인 전체(갱신 → 로그인 → 등록까지)의 시간 예산 |
| `syncTimeoutSeconds` | `10` | 동기화 요청 하나의 타임아웃(3장에서 쓴다) |

- 에셋이 없거나 `enabled == false`거나 `baseUrl`이 비어 있으면 오프라인이다.
- **밸런스 프로필을 적용한 실행(에디터·개발 빌드의 테스트 도구)은 항상 오프라인이다.** 콘텐츠가 서버와 달라 기록이 거부되기 때문이다.

### 2.4 인터페이스 (C#, `BlackHole.Net`)

```csharp
namespace BlackHole.Net
{
    // ---------- 전송 ----------

    public enum HttpOutcome
    {
        Response,       // 서버가 답했다(2xx, 4xx, 5xx 모두). StatusCode와 Body를 본다.
        NetworkError,   // 연결 실패, DNS 실패 등 서버에 닿지 않았다.
        Timeout,        // 시간 안에 답이 없었다.
    }

    public sealed class HttpRequest
    {
        public string Method { get; }          // "GET", "POST"
        public string Path { get; }            // "/api/v1/auth/guest/login" (baseUrl 뒤에 붙는다)
        public string JsonBody { get; }        // 없으면 null
        public string BearerToken { get; }     // 없으면 null. 있으면 Authorization: Bearer <값>
        public int TimeoutSeconds { get; }
        public HttpRequest(string method, string path, string jsonBody, string bearerToken, int timeoutSeconds);
    }

    public sealed class HttpResult
    {
        public HttpOutcome Outcome { get; }
        public long StatusCode { get; }        // Response일 때만 의미 있다
        public string Body { get; }            // Response일 때만, 없으면 ""
        public ApiError Error { get; }         // Response이고 본문이 오류 형식이면, 아니면 null. 전송이 파싱해 넣는다
        public string TransportMessage { get; }// 닿지 않았을 때의 원인(디버깅용)
        public bool IsSuccess { get; }         // Response이고 2xx
        public bool Reached { get; }           // Response이고 5xx가 아님(서버가 처리하고 답했다)

        public static HttpResult Response(long statusCode, string body, ApiError error = null);
        public static HttpResult NetworkError(string message);
        public static HttpResult Timeout(string message = null);
    }

    // 서버 공통 오류 형식 {"error":{"code","message"}}.
    public sealed class ApiError
    {
        public string Code { get; }            // ApiErrorCodes 상수와 비교한다
        public string Message { get; }         // 디버깅용
    }

    public static class ApiErrorCodes
    {
        public const string InvalidRequest = "INVALID_REQUEST";
        public const string InvalidCredentials = "INVALID_CREDENTIALS";
        public const string Unauthorized = "UNAUTHORIZED";
        public const string InvalidRefreshToken = "INVALID_REFRESH_TOKEN";
        public const string PayloadTooLarge = "PAYLOAD_TOO_LARGE";
        public const string InternalError = "INTERNAL_ERROR";
    }

    public interface IHttpTransport
    {
        // 예외를 던지지 않는다. 닿지 않은 경우도 HttpResult로 돌려준다.
        Task<HttpResult> SendAsync(HttpRequest request);
    }

    // JSON 변환. 이 어셈블리는 Unity에 의존하지 않으므로 구현(JsonUtility)은 BlackHole.Net.Unity에 둔다.
    // DTO는 [Serializable] 클래스의 public 필드로 만든다.
    public interface IJsonSerializer
    {
        string ToJson(object value);
        T FromJson<T>(string json) where T : class;   // 형식이 맞지 않으면 null
    }

    // API 경로: GuestRegister, GuestLogin, Refresh, Sync
    public static class ApiPaths { }

    // 인증 DTO: GuestRegisterResponseDto, GuestLoginRequestDto, RefreshTokenRequestDto, TokenResponseDto
    // (필드 이름 = API 명세의 JSON 키)

    // ---------- 계정 정보 ----------

    public sealed class GuestCredentials
    {
        public string GuestId { get; }
        public string GuestSecret { get; }
        public string RefreshToken { get; }    // 없으면 null
        public GuestCredentials WithRefreshToken(string refreshToken);
    }

    public interface ICredentialStore
    {
        // 없거나 읽지 못하면 null.
        GuestCredentials Load();
        // 원자적으로 쓴다(2.5). 실패하면 예외.
        void Save(GuestCredentials credentials);
    }

    // ---------- 인증 ----------

    public enum ConnectionState
    {
        Connecting,     // 앱 시작 직후, 로그인·시작 동기화 중
        Online,
        Offline,
    }

    public enum OfflineReason
    {
        None,
        Disabled,            // 설정이 꺼져 있음, 테스트 프로필
        Unreachable,         // 네트워크 오류, 타임아웃, 5xx, 시간 예산 초과
        CredentialsRejected, // 저장된 guestId·guestSecret이 거부됨(2.7)
        ProtocolError,       // 예상하지 못한 응답(400, 형식 오류 등). 버그
        ContentOutdated,     // 동기화가 CONTENT_VERSION_MISMATCH로 거부됨(3.6). 클라이언트 업데이트가 필요하다. (추후 구현, 아래 참고)
    }

    // 로그인한 세션. 동기화(3장)가 이걸로 요청에 토큰을 붙이고, 401이면 갱신한다.
    public interface IAuthSession
    {
        ConnectionState State { get; }
        OfflineReason OfflineReason { get; }
        event Action<ConnectionState> StateChanged;

        // 앱 시작 시 한 번 부른다. 2.6의 흐름을 loginBudgetSeconds 안에서 진행한다.
        // 끝나면 State가 Online 또는 Offline이다. 예외를 던지지 않는다.
        Task StartAsync();

        // Online일 때 현재 액세스 토큰. 아니면 null.
        string AccessToken { get; }

        // 요청이 401 UNAUTHORIZED를 받았을 때 부른다. 갱신에 성공하면 true(같은 요청을 한 번 다시 보낸다).
        // 실패하면 false이고 State는 Offline이 된다.
        Task<bool> RefreshAfterUnauthorizedAsync();

        // 동기화 등이 서버에 닿지 않았을 때 부른다. 이번 실행은 오프라인이 된다(D9).
        void GoOffline(OfflineReason reason);
    }
}
```

- 구현 클래스 이름과 내부 구조는 W2-2 담당이 정해도 된다. 위 공개 시그니처만 지킨다.
- 동기화(3장)와 타이틀 화면은 `IAuthSession`만 본다. 토큰과 계정 정보를 직접 다루지 않는다.
- `State`가 `Online`이 되는 시점은 로그인 성공 직후다. 시작 동기화(3장)가 끝날 때까지 시작 버튼을 막는 것은 타이틀 쪽이 동기화 결과까지 보고 정한다(2.8).

### 2.5 계정 정보 파일

- 위치: **진행 저장과 같은 폴더**의 `account.json`(릴리즈는 `Application.persistentDataPath`).
  - 테스트 도구가 진행 저장 폴더를 바꾸면 계정도 따라 바뀐다. 진행 저장과 계정이 항상 짝을 이룬다.
- 형식(`JsonUtility`, UTF-8 BOM 없음):

```json
{
  "formatVersion": 1,
  "guestId": "ccd9b782-14b3-4a62-a004-22951ad0eeb3",
  "guestSecret": "O4uOMSZI3-pBa3mb8aSS2xRuxzTT02AX3IglV4dxPSk",
  "refreshToken": "kObw44EmutxdPuBKFcIkN7eKsLc1amB79wSA128ucWU",
  "savedAtUtc": "2026-10-12T10:00:00Z"
}
```

- `refreshToken`은 없으면 빈 문자열이다(`GuestCredentials.RefreshToken`은 null).
- 쓰기는 `.tmp`에 쓴 뒤 바꿔치기한다(`AnalyticsInstall`, `ProgressSaveFile`과 같은 방식). 직전 파일(`.prev`)은 남기지 않는다(옛 refresh 토큰은 이미 폐기됐다).
- 읽지 못하는 파일(깨진 JSON, `guestId`·`guestSecret` 누락)은 `account.json.{시각}.bak`으로 옮기고 "계정 정보 없음"으로 본다.
- 액세스 토큰은 파일에 쓰지 않는다(15분짜리라 다음 실행에는 쓸 수 없다).
- 평문으로 저장한다. 게스트 계정의 한계로 수용한다.

### 2.6 로그인 흐름

`StartAsync`가 아래 순서로 진행한다. 전체가 `loginBudgetSeconds` 안에 끝나지 않으면 `Offline(Unreachable)`이다.

```
설정이 꺼져 있음 / 테스트 프로필 ─────────────────────────────────────────▶ Offline(Disabled)

계정 정보 없음 ─▶ 등록 ─▶ (guestId·guestSecret 저장) ─▶ 로그인 ─▶ (refreshToken 저장) ─▶ Online
계정 정보 있음
  ├ refreshToken 있음 ─▶ 갱신 ─ 성공 ─▶ (새 refreshToken 저장) ───────────────────────▶ Online
  │                         └ 401 INVALID_REFRESH_TOKEN ─▶ (refreshToken 지움) ─┐
  └ refreshToken 없음 ──────────────────────────────────────────────────────────┤
                                                                                ▼
                                   로그인 ─ 성공 ─▶ (refreshToken 저장) ─▶ Online
                                           └ 401 INVALID_CREDENTIALS ─▶ Offline(CredentialsRejected)
```

| 요청 | 결과 | 처리 |
|---|---|---|
| 등록 | 201 | **로그인하기 전에** guestId·guestSecret을 저장한다 |
| 로그인, 갱신 | 200 | **다른 일을 하기 전에** 새 refreshToken을 저장하고, 액세스 토큰은 메모리에 둔다 |
| 갱신 | 401 `INVALID_REFRESH_TOKEN` | refreshToken을 지우고(저장) 로그인으로 넘어간다 |
| 로그인 | 401 `INVALID_CREDENTIALS` | 오프라인(`CredentialsRejected`). **새 계정을 자동으로 만들지 않는다**(2.7) |
| 모든 요청 | 네트워크 오류, 타임아웃, 5xx | 오프라인(`Unreachable`). 계정 정보는 그대로 둔다 |
| 모든 요청 | 그 밖의 4xx, 형식이 맞지 않는 2xx 본문 | 오프라인(`ProtocolError`), 오류 로그 |

- 요청 하나의 타임아웃은 `min(requestTimeoutSeconds, 남은 예산)`이다(정수 초, 최소 1).
- 실행 중 401 처리(`RefreshAfterUnauthorizedAsync`): 갱신을 한 번 시도한다. 성공하면 true. 갱신이 401이면 로그인을 한 번 시도한다. 그래도 안 되면 false이고 `Offline`이 된다. 이 경로에는 시간 예산이 없고 요청마다 `requestTimeoutSeconds`만 적용한다.
- 갱신 요청은 동시에 하나만 보낸다. 이미 갱신 중이면 그 결과를 함께 기다린다(겹치면 서버 재사용 탐지에 걸린다).
- 로그아웃 API는 쓰지 않는다.

### 2.7 계정 정보가 거부될 때 새 계정을 만들지 않는 이유

저장된 guestId·guestSecret이 거부되는 것은 정상 운영에서는 일어나지 않는다(서버는 계정을 지우지 않는다). 개발 중 서버 DB를 초기화한 경우가 대부분이다.

이때 새 계정을 자동으로 만들면, 로컬의 다음 seq가 1이 아니라서 첫 동기화가 seq 누락으로 거부되고, 응답의 빈 진행도로 **로컬 진행도가 덮어써진다.** 그래서 자동으로 만들지 않고 오프라인으로 두며, 로컬 진행도는 그대로 남는다.

- 개발 중 복구 방법: 진행 저장 폴더의 계정 정보 파일과 진행 저장을 함께 지운다. W2-2에서 테스트 도구(개발 빌드 전용)에 "계정·진행 초기화" 버튼을 추가한다.

### 2.8 타이틀 화면

- 앱이 시작되면 타이틀을 바로 띄우고, 상태를 작은 텍스트로 표시한다.

| 상태 | 표시 (예) |
|---|---|
| `Connecting` | "서버 연결 중…" |
| `Online` | "온라인" |
| `Offline(Disabled)` | 표시 없음 |
| `Offline(Unreachable)` | "오프라인 — 기록은 다음 접속 때 반영됩니다" |
| `Offline(CredentialsRejected)`, `Offline(ProtocolError)` | "오프라인 — 계정을 확인할 수 없습니다" |
| `Offline(ContentOutdated)` | "오프라인 — 게임 데이터가 서버와 다릅니다. 업데이트가 필요합니다" (추후 구현) |

- **로그인과 시작 동기화가 끝날 때까지 시작 버튼을 막는다.** 시작 동기화가 끝나야 진행도가 확정되기 때문이다. 동기화(3장)가 구현되기 전까지는 로그인이 끝나면(`Online` 또는 `Offline`) 푼다.
- 시작 버튼을 막는 최대 시간은 `loginBudgetSeconds + syncTimeoutSeconds`(기본 18초)다. 서버에 닿지 않을 때는 대부분 연결 오류가 곧바로 와서 훨씬 짧다.

### 2.9 결정 사항 (2장, 확정)

| # | 결정 | 이유 |
|---|---|---|
| C6 | Unity 무관 어셈블리(`BlackHole.Net`)와 Unity 어셈블리(`BlackHole.Net.Unity`)로 나눈다 | 인증 흐름을 Editor 테스트에서 가짜 전송으로 검증한다. 통계 전송과 같은 구조 |
| C7 | 서버 설정은 새 `ServerSettings` 에셋에 둔다 | 통계 설정과 섞지 않는다. 합치는 것은 W5-1에서 검토 |
| C8 | 계정 정보는 진행 저장과 같은 폴더의 `account.json`에 둔다 | 진행 저장과 계정이 늘 짝을 이룬다. 테스트 프로필의 진행이 실제 계정에 섞이지 않는다 |
| C9 | 로그인이 `INVALID_CREDENTIALS`면 새 계정을 만들지 않고 오프라인으로 둔다 | 로컬 진행도가 빈 서버 진행도로 덮어써지는 것을 막는다 |
| C10 | 밸런스 프로필을 적용한 실행은 항상 오프라인 | 콘텐츠가 서버와 달라 기록이 거부된다 |
| C11 | 타임아웃: 요청 5초, 로그인 전체 8초, 동기화 요청 10초 | 시작 버튼을 막는 시간을 최대 18초로 제한한다 |

### 2.10 산출물

클라이언트 저장소.

| 산출물 | 위치 | 상태 |
|---|---|---|
| `BlackHole.Net` 어셈블리: 2.4의 타입·인터페이스 전체, `ApiPaths`, 인증 DTO | `Assets/Scripts/Net/` | 완료(구현 없는 인터페이스 포함) |
| `BlackHole.Net.Unity` 어셈블리: `ServerSettings` | `Assets/Scripts/Net/Unity/` | 완료. 에셋(`Assets/Data/ServerSettings.asset`)은 W2-3에서 만든다 |
| `BlackHole.Net.Tests` 어셈블리: `HttpResultTests` | `Assets/Tests/Net/` | 완료(5개 통과) |

W2-1·W2-2가 채울 것:

| 작업 | 구현 | 위치 |
|---|---|---|
| W2-1 | `IHttpTransport`의 `UnityWebRequest` 구현(오류 본문 파싱 포함), `IJsonSerializer`의 `JsonUtility` 구현, 가짜 전송 | `BlackHole.Net.Unity`, 테스트 |
| W2-2 | `IAuthSession` 구현(2.6 흐름), `ICredentialStore`의 파일 구현(2.5), 개발 빌드 "계정·진행 초기화" | 흐름은 `BlackHole.Net`, 파일은 `BlackHole.Net.Unity` |
| W2-3 | `ServerSettings` 에셋, `GameBootstrap` 연결, 타이틀 상태 표시와 시작 버튼 대기(2.8) | Assembly-CSharp |

---

## 3. 로그·동기화 JSON

통신 형식의 큰 틀은 [API 명세](api-spec.md#동기화)에 있다. 이 장은 그 형식의 **필드 타입·범위·필수 여부, 오류를 어디서 내는지, 한도, 거부 코드, 샘플**을 확정한다. 확정한 내용은 API 명세에도 반영한다.

### 3.1 원칙

- 형식은 API 명세의 `{ seq, type, occurredAtUtc, buildVersion, contentVersion, payload }`를 유지한다. `payload`는 `type`마다 다른 객체다.
- **로그는 만들어진 뒤 바뀌지 않는다.** 클라이언트는 로그를 만들 때 JSON 글자로 한 번 직렬화해 보관하고, 보낼 때 그 글자를 그대로 이어 붙인다. 재전송해도 같은 글자가 간다.
- 클라이언트의 `JsonUtility`는 null과 다형성(타입마다 다른 `payload`)을 쓰지 못한다. 그래서 **봉투(공통 필드)와 `payload`를 따로 직렬화해 글자로 합친다**(구현은 5장). 서버가 받는 JSON은 이 장의 형식 그대로다.
- **잘못된 로그 하나 때문에 요청 전체를 버리지 않는다.** 로그 하나의 형식 오류는 그 로그의 거부(`MALFORMED_LOG`)로 처리해, 앞의 로그는 반영되고 클라이언트는 그 로그부터 폐기할 수 있게 한다. 요청 전체의 400은 로그를 하나도 구분할 수 없을 때만 쓴다(3.4).

### 3.2 요청

```json
{ "logs": [ { ...로그 }, ... ] }
```

| 필드 | 타입 | 규칙 |
|---|---|---|
| `logs` | 배열 | 필수. 빈 배열 허용. 최대 500개(3.5) |

### 3.3 로그

**공통 필드**

| 필드 | 타입 | 규칙 |
|---|---|---|
| `seq` | 정수(int64) | 필수, 1 이상. 요청 안에서 **1씩 증가**(3.4) |
| `type` | 문자열 | 필수. `RUN_START`, `SETTLEMENT`, `NODE_PURCHASE`, `RESET` |
| `occurredAtUtc` | 문자열 | 필수, 1~40자. ISO-8601 UTC(예: `2026-10-12T10:00:00Z`). 기록용이며 해석하지 않는다 |
| `buildVersion` | 문자열 | 필수, 0~32자 |
| `contentVersion` | 문자열 | 필수, 0~64자. **서버 콘텐츠 버전과 같아야 한다.** 다르면 `CONTENT_VERSION_MISMATCH`(3.6) |
| `payload` | 객체 | 필수. `RESET`은 `{}` |

**`NODE_PURCHASE`**

| 필드 | 타입 | 규칙 |
|---|---|---|
| `nodeId` | 문자열 | 필수, 1~64자 |
| `rank` | 정수(int32) | 필수, 1 이상. 구매 후 Rank |
| `cost` | 정수(int64) | 필수, 0 이상 |

**`RESET`**: 필드 없음. 다른 필드가 있어도 무시한다.

**`RUN_START`**

| 필드 | 타입 | 규칙 |
|---|---|---|
| `runId` | 문자열 | 필수. 소문자 UUID(36자, `8-4-4-4-12`) |
| `seed` | 정수(int32) | 필수 |
| `startGrowthStage` | 정수(int32) | 필수, 0 이상 |
| `startGold` | 정수(int64) | 필수, 0 이상 |
| `startLevel` | 정수(int32) | 필수, 0 이상 |
| `timeLimitSeconds` | 실수(float) | 필수, 0보다 큰 유한한 값 |
| `startSupply` | 배열 | 필수, 0~20개. 항목 `{ enemyId: 문자열 1~32자, count: int32 1 이상 }`, `enemyId` 중복 없음 |

**`SETTLEMENT`**

| 필드 | 타입 | 규칙 |
|---|---|---|
| `runId` | 문자열 | 필수. 소문자 UUID |
| `playedSeconds` | 실수(float) | 필수, 0 이상의 유한한 값 |
| `kills` | 배열 | 필수, 0~2000개. 항목 `{ enemyId: 문자열 1~32자, tier: int32 0 이상, size: int32 1 이상, golden: 불리언, count: int32 1 이상 }`. (enemyId, tier, size, golden) 중복 없음 |
| `goldenCritCount` | 정수(int32) | 필수, 0 이상 |
| `goldenCritBonusGold` | 정수(int64) | 필수, 0 이상 |
| `earnedGold` | 정수(int64) | 필수, 0 이상 |
| `exp` | 정수(int64) | 필수, 0 이상 |
| `reachedLevel` | 정수(int32) | 필수, 0 이상 |
| `reachedMilestone` | 불리언 | 필수 |
| `timeBonusCount` | 정수(int32) | 필수, 0 이상 |
| `settledGold` | 정수(int64) | 필수, 0 이상 |
| `nextGrowthStage` | 정수(int32) | 필수, 0 이상 |

- `kills`의 2000은 (종류 5 × 색 6 × 크기 20 × 황금 2 = 1200)보다 넉넉한 상한이다.
- 실수는 JSON 숫자로 보낸다. 서버는 `float`로 읽는다(계약 1.7과 같은 타입).
- 정의되지 않은 필드는 무시한다(앞으로 필드를 더할 때 옛 서버가 거부하지 않게).

### 3.4 오류를 어디서 내는가

| 상황 | 결과 |
|---|---|
| 본문이 JSON이 아님, `logs`가 없거나 배열이 아님 | `400 INVALID_REQUEST` (아무것도 적용하지 않음) |
| 어떤 로그의 `seq`가 없거나 정수가 아님, 요청 안에서 `seq`가 1씩 증가하지 않음 | `400 INVALID_REQUEST` (아무것도 적용하지 않음) |
| `logs`가 500개 초과, 본문이 1 MB 초과 | `413 PAYLOAD_TOO_LARGE` |
| 로그의 `seq`는 읽히지만 나머지 공통 필드나 `payload`가 3.3을 어김(필드 누락, 타입 오류, 범위 밖, 알 수 없는 `type`, 중복 항목) | 그 로그를 `MALFORMED_LOG`로 **거부하고 멈춘다**. 앞의 로그는 반영된다 |
| 형식은 맞지만 규칙 검사에 걸림 | 그 로그를 3.6의 코드로 거부하고 멈춘다 |

- 로그 하나의 검사 순서: `seq`(이미 반영됨 → 건너뜀, 누락 → `SEQ_GAP`) → 형식(`MALFORMED_LOG`) → 콘텐츠 버전(`CONTENT_VERSION_MISMATCH`) → 타입별 규칙. 이미 반영된 로그는 형식이나 버전이 어긋나도 건너뛴다.
- 클라이언트가 400을 받으면 클라이언트 버그다. 이번 실행은 오프라인으로 두고 오류 로그를 남긴다(대기 로그는 지우지 않는다).

### 3.5 한도

| 항목 | 값 | 근거 |
|---|---|---|
| 요청당 로그 수 | 500개 | 결산 로그 하나가 칸 100개 기준 약 6 KB, 구매 로그는 약 0.2 KB |
| 요청 본문 | 1 MB | 위 기준으로 500개가 들어간다 |

- 클라이언트는 대기 로그를 **seq 순서대로 최대 500개씩, 본문 1 MB 이하로** 나눠 보낸다. 앞 요청의 응답을 받은 뒤 다음 요청을 보낸다. 앞 요청에 거부가 있으면 뒤 요청은 보내지 않는다.

### 3.6 거부 코드

`rejected.code`의 전체 목록이다. 이 목록 밖의 코드는 쓰지 않는다.

| code | 로그 | 의미 |
|---|---|---|
| `SEQ_GAP` | 공통 | `seq`가 `lastAppliedSeq + 1`보다 큼(앞 로그 누락) |
| `MALFORMED_LOG` | 공통 | 3.3의 형식 위반 |
| `CONTENT_VERSION_MISMATCH` | 공통 | `contentVersion`이 서버 콘텐츠 버전과 다름 |
| `UNKNOWN_NODE` | `NODE_PURCHASE` | 서버 노드 콘텐츠에 없거나 배치되지 않은 노드 |
| `MAX_RANK_REACHED` | `NODE_PURCHASE` | 이미 최대 Rank |
| `NODE_HIDDEN` | `NODE_PURCHASE` | 아직 드러나지 않은 노드 |
| `NOT_ENOUGH_GOLD` | `NODE_PURCHASE` | Gold 부족 |
| `RANK_MISMATCH` | `NODE_PURCHASE` | `rank`가 현재 Rank + 1이 아님 |
| `COST_MISMATCH` | `NODE_PURCHASE` | `cost`가 서버 비용과 다름 |
| `DUPLICATE_RUN_ID` | `RUN_START` | 이미 쓰인 `runId` |
| `RUN_NOT_OPEN` | `SETTLEMENT` | 열린 판이 없거나 `runId`가 다름(S1) |
| `SETTLEMENT_INVALID_KILLS` | `SETTLEMENT` | 이번 판에서 불가능한 장부 항목(S2) |
| `SETTLEMENT_EXP_MISMATCH` | `SETTLEMENT` | EXP, Level, 이정표가 장부와 맞지 않음(S3, S4) |
| `SETTLEMENT_GOLD_MISMATCH` | `SETTLEMENT` | Gold, 치명 보너스가 장부와 맞지 않음(S5, S6) |
| `SETTLEMENT_TIME_EXCEEDED` | `SETTLEMENT` | 시간 추가 횟수나 경과 시간이 가능한 범위를 넘음(S7, S8) |
| `SETTLEMENT_RESULT_MISMATCH` | `SETTLEMENT` | 결산 금액이나 결산 후 성장도가 서버 계산과 다름(S9) |

- 검사 순서는 3.4의 순서, 타입별 규칙 안에서는 위 표의 순서이고, 처음 걸린 것 하나만 알린다.
- 클라이언트는 `CONTENT_VERSION_MISMATCH`를 받으면 다른 거부와 같이 처리(대기 로그 폐기, 서버 진행도 채택)한 뒤, **이번 실행을 오프라인(`ContentOutdated`)으로 바꾼다.** 버전이 다른 채로 계속 보내면 로그마다 거부되어 진행도가 되돌아가기 때문이다.
- **구현 시점**: 서버의 거부(`CONTENT_VERSION_MISMATCH`)는 이번 범위에서 구현한다. 클라이언트의 `ContentOutdated` 전환과 표시는 **추후 구현**이며, 그 전까지 클라이언트는 이 코드를 다른 거부와 똑같이 처리한다. `OfflineReason.ContentOutdated` 값은 W2 작업에서 추가하지 않아도 된다.
- `message`는 사람이 읽는 설명이며 형식을 정하지 않는다.
- `RUN_START`의 값 불일치는 거부가 아니므로 코드가 없다. 의심 표시의 사유는 4장에서 정한다.

### 3.7 응답

API 명세의 형식 그대로다. `progress`는 `GET /me/progress`와 같은 객체(`lastAppliedSeq`, `gold`, `growthStage`, `nodes`, `updatedAt`)다.

| 필드 | 타입 | 규칙 |
|---|---|---|
| `appliedThroughSeq` | int64 | 처리 후 `lastAppliedSeq` |
| `rejected` | 객체 또는 `null` | `{ seq: int64, type: 문자열, code: 문자열, message: 문자열 }`. 형식 오류로 `type`을 알 수 없으면 `type`은 `""` |
| `contentVersion` | 문자열 | 서버 콘텐츠 묶음의 버전 |
| `progress` | 객체 | 항상 있다 |

- 클라이언트의 `JsonUtility`는 `null` 객체를 읽으면 빈 객체로 채운다. **`rejected`가 있는지는 `rejected.code`가 빈 문자열이 아닌지로 판단한다.**

### 3.8 샘플

샘플 콘텐츠 묶음(1.9, `contentVersion` = `4d6c398ad1f5e49c`)에서 **실제로 검증을 통과하는** 값으로 만들었다. 두 저장소에 같은 파일을 둔다.

- 서버: `src/test/resources/sync-samples/`
- 클라이언트: `Assets/Tests/Net/SyncSamples/`

| 파일 | 내용 |
|---|---|
| `sync-request.json` | 새 계정에서: 판 시작 → 결산(소행성 20마리, Gold 20) → `timer-01` 구매(비용 2) → 판 시작(제한 시간 14초) → 결산(18마리) → 새 게임. seq 1~6 |
| `sync-response-applied.json` | 위 요청의 응답. 모두 반영, 새 게임으로 진행도 0 |
| `sync-request-tampered.json` | 위 요청의 seq 1~3에서 seq 2 결산의 Gold를 99999로 부풀린 것 |
| `sync-response-rejected.json` | 위 요청의 응답. seq 1까지만 반영, seq 2가 `SETTLEMENT_GOLD_MISMATCH` |
| `sync-request-empty.json` | 로그 없는 요청(진행도만 받는다) |
| `log-run-start.json`, `log-settlement.json`, `log-node-purchase.json`, `log-reset.json` | 타입별 로그 하나 |

- 클라이언트 로그 생성기(W3-2)가 만든 JSON은 이 샘플과 **같은 키·같은 타입**이어야 한다(키 순서와 공백은 달라도 된다). W4-3의 계약 테스트가 이것을 확인한다.
- 서버 처리기 테스트(W1-3, W1-5, W1-6)와 E2E(W1-8)는 이 샘플을 기본 시나리오로 쓴다.

### 3.9 결정 사항 (3장, 확정)

| # | 결정 | 이유 |
|---|---|---|
| C12 | 로그는 만들 때 한 번 JSON 글자로 직렬화해 보관하고 그대로 보낸다. 봉투와 `payload`는 따로 직렬화해 합친다 | `JsonUtility`의 제약(null, 다형성)을 피하고, 재전송 때 같은 글자를 보장한다 |
| C13 | 요청 전체 400은 `logs`나 `seq`를 읽을 수 없을 때만. 나머지 형식 오류는 그 로그의 `MALFORMED_LOG` | 잘못된 로그 하나 때문에 이후 모든 동기화가 막히지 않는다 |
| C14 | 한도: 요청당 500개, 1 MB. 클라이언트가 나눠 보낸다 | 오프라인으로 쌓인 로그도 몇 번의 요청으로 보낸다 |
| C15 | 정의되지 않은 필드는 무시한다 | 필드를 더할 때 옛 서버가 거부하지 않는다 |
| C16 | `contentVersion`이 서버와 다르면 `CONTENT_VERSION_MISMATCH`로 거부한다. 클라이언트는 그 실행을 오프라인(`ContentOutdated`)으로 바꾼다 | 계획서 D3. 최근 몇 버전의 규칙을 보관해 하위 호환을 허용하는 것은 후속 과제(W5-3) |

---

## 4. 서버 스키마와 처리기

동기화 엔진(W1-2, A)과 로그 타입별 처리기(W1-3·W1-5 B, W1-6 A)의 경계를 정한다. 엔진은 요청을 받아 로그를 하나씩 처리기에 넘기고, 처리기는 검증하고 진행도를 바꾼다.

### 4.1 책임 나누기

| 단계 | 담당 | 내용 |
|---|---|---|
| 요청 형식 | 엔진 | `logs` 배열, `seq` 읽기·연속성(3.4의 400), 한도(413) |
| 잠금 | 엔진 | 계정 진행도 행을 `PESSIMISTIC_WRITE`로 잠근다. 요청 하나가 트랜잭션 하나 |
| seq | 엔진 | 이미 반영됨 → 건너뜀, 누락 → `SEQ_GAP` |
| 형식 | 엔진 | 공통 필드와 `payload`를 3.3에 맞춰 읽고 검사 → 어긋나면 `MALFORMED_LOG`. 처리기는 **검사를 통과한 payload만** 받는다 |
| 콘텐츠 버전 | 엔진 | 서버 `contentVersion`과 비교 → `CONTENT_VERSION_MISMATCH` |
| 규칙 검사와 적용 | 처리기 | 타입별 규칙(계획서 6.2). 통과하면 `SyncContext`로 진행도를 바꾼다 |
| 기록·응답 | 엔진 | `sync_log` 기록, `lastAppliedSeq` 갱신, 응답 |

- 처리기는 HTTP, JSON, 저장소(Repository)를 모른다. `SyncContext`(4.3)와 콘텐츠 빈(`NodeTree`, `BattleContent`), `BattleRules`만 쓴다.
- 처리기는 **검사를 모두 마친 뒤에만** `SyncContext`의 변경 메서드를 부른다. 거부할 때는 아무것도 바꾸지 않는다. 엔진은 거부와 함께 변경이 있었으면 버그로 보고 예외를 던진다(요청 전체가 롤백되고 500).
- 처리 중 예상하지 못한 예외는 요청 전체를 롤백하고 500이다. 클라이언트는 서버에 닿지 않은 것으로 보고 로그를 지우지 않는다(2.1).

### 4.2 스키마 (V3)

```sql
-- 진행도: 마지막으로 반영한 로그 seq, 열린 판, 의심 표시 수.
ALTER TABLE player_progress
    ADD COLUMN last_applied_seq BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN open_run_id      BIGINT NULL,
    ADD COLUMN suspicious_count INT    NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_player_progress_last_applied_seq CHECK (last_applied_seq >= 0),
    ADD CONSTRAINT ck_player_progress_suspicious_count CHECK (suspicious_count >= 0);

-- 판 하나(RUN_START로 열고 SETTLEMENT로 결산, 또는 포기로 닫는다).
CREATE TABLE battle_run (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    account_id         BIGINT       NOT NULL,
    run_id             CHAR(36)     NOT NULL,
    status             VARCHAR(16)  NOT NULL,      -- OPEN, SETTLED, ABANDONED
    seed               INT          NOT NULL,
    start_growth_stage INT          NOT NULL,      -- 서버 진행도 기준(클라이언트가 보낸 값이 아님)
    start_gold         BIGINT       NOT NULL,      -- 서버 진행도 기준
    started_seq        BIGINT       NOT NULL,
    ended_seq          BIGINT       NULL,
    settled_gold       BIGINT       NULL,          -- 서버가 계산한 결산 금액
    next_growth_stage  INT          NULL,
    suspicious_reasons VARCHAR(255) NULL,          -- 의심 사유 코드를 쉼표로(4.4)
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_battle_run_account_run UNIQUE (account_id, run_id),
    CONSTRAINT fk_battle_run_account FOREIGN KEY (account_id) REFERENCES account (id)
);

ALTER TABLE player_progress
    ADD CONSTRAINT fk_player_progress_open_run FOREIGN KEY (open_run_id) REFERENCES battle_run (id);

-- 받은 로그(감사·진단). 같은 seq가 여러 번 올 수 있다(재전송, 거부 뒤 재사용).
CREATE TABLE sync_log (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    account_id      BIGINT      NOT NULL,
    seq             BIGINT      NOT NULL,
    type            VARCHAR(32) NOT NULL,          -- 받은 글자 그대로. 읽지 못했으면 ''
    result          VARCHAR(16) NOT NULL,          -- APPLIED, REJECTED, SKIPPED, NOT_PROCESSED
    reject_code     VARCHAR(40) NULL,
    content_version VARCHAR(64) NOT NULL,          -- 읽지 못했거나 너무 길면 ''
    build_version   VARCHAR(32) NOT NULL,
    raw_json        JSON        NOT NULL,          -- 받은 로그 객체 그대로
    received_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX ix_sync_log_account_seq (account_id, seq),
    CONSTRAINT fk_sync_log_account FOREIGN KEY (account_id) REFERENCES account (id)
);
```

- `revision` 열은 옛 저장 API를 없앨 때(W1-7) 함께 지운다(V4, A가 작성).
- `battle_run`은 계정 안에서 `run_id`가 유일하다. 다른 계정과 겹쳐도 상관없다.
- `NOT_PROCESSED`는 거부된 로그 뒤에 있어 처리하지 않은 로그다.

### 4.3 처리기 인터페이스 (Java)

패키지 `org.example.feedablackhole.sync`.

```java
// sync.log
public enum LogType { RUN_START, SETTLEMENT, NODE_PURCHASE, RESET }

public enum RejectCode {
    SEQ_GAP, MALFORMED_LOG, CONTENT_VERSION_MISMATCH,
    UNKNOWN_NODE, MAX_RANK_REACHED, NODE_HIDDEN, NOT_ENOUGH_GOLD, RANK_MISMATCH, COST_MISMATCH,
    DUPLICATE_RUN_ID,
    RUN_NOT_OPEN, SETTLEMENT_INVALID_KILLS, SETTLEMENT_EXP_MISMATCH, SETTLEMENT_GOLD_MISMATCH,
    SETTLEMENT_TIME_EXCEEDED, SETTLEMENT_RESULT_MISMATCH
}

// sync.log.payload: 3.3의 payload. 엔진이 형식 검사(Bean Validation + 중복·유한성 검사)를 마친 뒤 처리기에 넘긴다.
// 코드에서는 누락을 잡기 위해 감싼 타입(Integer, Long, Float, Boolean)과 검증 애너테이션을 쓴다. 처리기가 받을 때는 null인 필드가 없다.
public record NodePurchasePayload(String nodeId, int rank, long cost) {}
public record ResetPayload() {}
public record RunStartPayload(String runId, int seed, int startGrowthStage, long startGold, int startLevel,
                              float timeLimitSeconds, List<SupplyItem> startSupply) {
    public record SupplyItem(String enemyId, int count) {}
}
public record SettlementPayload(String runId, float playedSeconds, List<KillItem> kills,
                                int goldenCritCount, long goldenCritBonusGold, long earnedGold, long exp,
                                int reachedLevel, boolean reachedMilestone, int timeBonusCount,
                                long settledGold, int nextGrowthStage) {
    public record KillItem(String enemyId, int tier, int size, boolean golden, int count) {}
}

// sync.handler
public interface LogHandler<P> {
    LogType type();
    Class<P> payloadType();
    /** 규칙을 검사하고, 통과하면 context로 진행도를 바꾼다. 거부하면 아무것도 바꾸지 않는다. */
    HandleResult handle(P payload, SyncContext context);
}

public sealed interface HandleResult {
    record Applied() implements HandleResult {}
    record Rejected(RejectCode code, String message) implements HandleResult {}
    static HandleResult applied();
    static HandleResult rejected(RejectCode code, String message);
}

/** 처리 중인 계정의 진행도. 엔진이 잠근 엔티티 위에 구현한다. 처리기는 이것만으로 읽고 바꾼다. */
public interface SyncContext {
    long accountId();
    long seq();                              // 지금 처리 중인 로그의 seq

    // ---- 읽기 ----
    long gold();
    int growthStage();
    Map<String, Integer> nodeRanks();        // 산 노드(nodeId → rank), 처음 산 순서, 읽기 전용
    Optional<OpenRun> openRun();
    boolean runIdUsed(String runId);         // 이 계정에서 이미 쓴 runId인가

    // ---- 변경 (검사를 모두 통과한 뒤에만) ----
    void spendGold(long amount);             // amount ≤ gold()
    void earnGold(long amount);
    void setNodeRank(String nodeId, int rank);  // 새 노드면 처음 산 순서의 끝에 붙는다
    void setGrowthStage(int growthStage);
    void resetProgress();                    // Gold 0, 성장도 0, 산 노드 없음. 열린 판은 포기로 닫는다
    void abandonOpenRun();                   // 열린 판이 없으면 아무것도 하지 않는다
    void openRun(RunStartPayload payload, List<SuspicionReason> suspicions);
                                             // 열린 판이 있으면 먼저 abandonOpenRun()을 부른다(처리기 책임)
    void settleOpenRun(long settledGold, int nextGrowthStage);
                                             // 열린 판을 결산 완료로 닫는다. Gold·성장도 반영은 earnGold·setGrowthStage로 따로 한다
}

/** 열린 판. 판 시작 때 서버 진행도 기준의 값. */
public record OpenRun(String runId, int seed, int startGrowthStage, long startGold, long startedSeq) {}

/** RUN_START가 서버 계산과 다를 때 남기는 의심 사유(4.4). */
public enum SuspicionReason {
    START_GROWTH_STAGE_MISMATCH, START_GOLD_MISMATCH, START_LEVEL_MISMATCH,
    TIME_LIMIT_MISMATCH, START_SUPPLY_MISMATCH
}
```

- 처리기는 Spring 빈으로 등록하고, 엔진은 `LogHandler` 빈들을 `type()`으로 찾는다. 같은 타입이 둘 이상이거나 `payloadType()`이 3.3의 타입과 다르면 서버를 시작하지 않는다. 처리기가 없는 타입은 처리기를 나눠 구현하는 동안 경고만 남기고, 그 타입의 로그가 오면 500이다(W1-2 구현).
- 결산 처리기는 판을 닫는 것(`settleOpenRun`)과 진행도 반영(`earnGold`, `setGrowthStage`)을 둘 다 부른다.

### 4.4 의심 표시

- `RUN_START`의 `startGrowthStage`, `startGold`, `startLevel`, `timeLimitSeconds`, `startSupply`를 서버 계산과 비교해, 다른 것마다 `SuspicionReason`을 하나씩 남긴다. 거부하지 않고 판은 연다(계획서 D8).
- `timeLimitSeconds`는 `|클라이언트 − 서버| ≤ 0.001`이면 같다고 본다. 나머지는 정확히 같아야 한다(`startSupply`는 순서까지).
- 사유가 하나 이상이면 그 판의 `suspicious_reasons`에 쉼표로 적고, 계정의 `suspicious_count`를 1 올린다(판 하나당 1).
- 의심 표시는 기록일 뿐이며 이후 처리에 영향을 주지 않는다. 조회 API는 만들지 않는다(DB에서 본다).

### 4.5 결정 사항 (4장, 확정)

| # | 결정 | 이유 |
|---|---|---|
| C17 | 형식 검사(3.3)와 seq·버전 검사는 엔진, 규칙 검사는 처리기 | 처리기는 형식이 맞는 payload만 다루고, 거부 코드의 위치가 한곳에 모인다 |
| C18 | 처리기는 `SyncContext`로만 진행도를 읽고 바꾸며, 거부할 때는 바꾸지 않는다 | 처리기가 저장소와 트랜잭션을 몰라도 된다. 한 로그 안에서 일부만 반영되는 일이 없다 |
| C19 | 요청 하나가 트랜잭션 하나. 거부는 커밋, 예상하지 못한 예외는 전체 롤백(500) | 거부 직전까지는 반영하고, 버그로 진행도가 어긋나는 일은 없게 한다 |
| C20 | `battle_run`의 시작 Gold·성장도는 서버 진행도 값으로 적는다 | 결산 검증의 기준은 서버 값이다 |
| C21 | `revision` 열은 W1-7(옛 API 제거) 때 지운다 | 옛 API와 그 테스트가 W1-7 전까지 그대로 돌게 한다 |
| C22 | `timeLimitSeconds` 비교 허용 오차 0.001초 | 같은 float 계산이면 같은 값이 나오므로 작게 둔다 |

### 4.6 엔진 구현 메모 (W1-2)

- 위치: `sync/controller/SyncController`, `sync/service/`(`SyncRequestReader` 요청 전체 400·413, `LogParser` 로그 형식, `SyncService` 엔진, `JpaSyncContext`, `LogHandlerRegistry`), `sync/dto/`
- 요청 본문은 글자로 받아 직접 읽는다. 로그 하나의 형식 오류를 요청 전체의 400이 아니라 그 로그의 `MALFORMED_LOG`로 처리하기 위해서다.
- 서버 콘텐츠 버전은 `ServerContentVersion` 빈이 준다. 시작할 때 검증한 묶음 목차의 `contentVersion`(`ContentManifest`, W0-3)이다(`sync/config/SyncConfig`).
- 진행도가 바뀌면 옛 저장 API와 섞이지 않도록 `revision`도 오른다(W1-7에서 제거).
- 새 게임은 산 노드 행을 하나씩 지운다. 벌크 삭제가 영속성 컨텍스트를 비워 잠근 진행도 엔티티까지 떼어 내기 때문이다.
- 테스트(`SyncApiTests`, 19개)는 빈으로 등록하지 않은 테스트용 처리기로 엔진의 몫만 본다(실제 처리기가 생겨도 겹치지 않는다).

### 4.7 산출물

서버 저장소 `dev` 기준.

| 산출물 | 위치 | 상태 |
|---|---|---|
| V3 마이그레이션 | `src/main/resources/db/migration/V3__create_sync_tables.sql` | 완료(= W1-1) |
| 엔티티·저장소 | `PlayerProgress`(열 3개 추가), `sync/entity/BattleRun`, `BattleRunStatus`, `SyncLogRecord`, `SyncLogResult`, `sync/repository/*` | 완료. 진행도·판 변경 메서드는 W1-2에서 필요한 만큼 더한다 |
| 로그 타입·거부 코드·payload | `sync/log/LogType`, `RejectCode`, `sync/log/payload/*` | 완료 |
| 처리기 경계 | `sync/handler/LogHandler`, `HandleResult`, `SyncContext`, `OpenRun`, `SuspicionReason` | 완료(구현 없음) |
| 테스트 | `SyncSchemaTests`(6개) | 통과 |

- `sync_log` 테이블의 엔티티 이름은 `SyncLogRecord`다(로그 자체와 구분).
- 1장의 코드(`content/`, `battle/`)는 `feat/load-battle-content` 브랜치에만 있고 `dev`에는 아직 없다. 4장 코드는 그것에 의존하지 않는다. 처리기 구현(W1-3, W1-5, W1-6)이 `NodeTree`·`BattleRules`를 쓰므로, W0-3이 `dev`에 병합된 뒤 시작한다.

---

## 5. 클라이언트 로그와 동기화

클라이언트가 로그를 만들고(W3-1 C, W3-2 A), 진행도와 함께 저장하고(W3-3 A), 서버로 보내고 응답을 반영하는(W3-4 C, W3-5 C) 경계를 정한다.

### 5.1 흐름

```
[게임]  판 시작·결산·노드 구매·새 게임
   │  ① 로컬 진행도에 바로 반영(지금과 같음)
   │  ② 로그 생성(SyncLogFactory, W3-2) → JSON 글자 하나
   │  ③ 진행도 + 로그를 한 번에 저장(ISyncStore, W3-3) → LogAppended 이벤트
   ▼
[전송]  SyncSender(W3-4)
   │  앱 시작: 로그인 끝나면 쌓인 로그 전부(없으면 빈 배열) 전송 → 응답 반영 → 시작 버튼 풀림
   │  온라인 중: LogAppended마다 전송(동시에 하나만)
   │  ④ ISyncApi.SendAsync(로그들) (W3-4) → 서버
   │  ⑤ ISyncStore.ApplyResponse(응답) (W3-3) → 로그 정리, 필요하면 진행도 덮어쓰기, 거부 기록
   ▼
[표시]  거부 기록이 있으면 타이틀·업그레이드 화면에 한 번 알림(W3-5)
```

### 5.2 어셈블리

| 어셈블리 | Unity 의존 | 내용 | 담당 |
|---|---|---|---|
| `BlackHole.Net` (2장) | 없음 | 동기화 DTO(5.4), `SyncJson`(5.5), `ISyncApi`, `ISyncStore`, `PendingLog` 등(5.6) | A(타입), C(`ISyncApi` 구현) |
| `BlackHole.Sync` (신규) | 없음 | `SyncLogFactory`: 코어 객체(진행도, 판, 결산 결과)로 로그 JSON을 만든다. `BlackHole.Core.*`와 `BlackHole.Net`을 참조 | A |
| Assembly-CSharp | 있음 | `ISyncStore` 구현(진행 저장과 함께, `Save/`), `SyncSender`, 연결 코드, 알림 UI | A(저장), C(전송·UI) |

- `SyncLogFactory`를 Unity 밖에 두는 이유: `Tools/Simulation`(net8.0)이 캠페인 전체 로그를 만들 때(W4-2a) 같은 코드를 쓴다. 그때 `IJsonSerializer`는 `System.Text.Json`(`IncludeFields = true`)으로 구현한다.

### 5.3 코어에 더할 것

**W3-1 (C): 칸별 처치 집계** — `BlackHole.Core`

```csharp
// 처치 장부의 칸: (종류, 색 등급, 크기, 황금 여부). 수거형(혜성)은 tier 0, size 1, golden false.
public readonly struct KillBucket : IEquatable<KillBucket>
{
    public EnemyDefinition Enemy { get; }
    public int Tier { get; }        // 0부터
    public int Size { get; }        // 1부터
    public bool Golden { get; }     // 성질이 황금(GoldenDefinition)인가
    public KillBucket(EnemyDefinition enemy, int tier, int size, bool golden);
}

public readonly struct KillBucketCount
{
    public KillBucket Bucket { get; }
    public int Count { get; }       // 1 이상
}

// BattleRawData에 더한다(기존 속성은 그대로).
public IReadOnlyList<KillBucketCount> KillBuckets { get; }   // 처음 처치한 순서, 0인 칸 없음
public int GoldenCritCount { get; }                           // 황금 보너스 치명 횟수
public long GoldenCritBonusGold { get; }                      // 그 보너스 Gold 합
public int TimeBonusCount { get; }                            // 처치 시 시간 추가 성공 횟수
```

- 세는 시점은 기존 처치 수·Gold와 같은 곳(사망 확정 순간, `EnemyRoster`, `DeathRewards`, `DeathBonuses`)이다.
- 판이 끝난 뒤 치운 적(`ClearRemainingEnemies`)과 테스트 도구가 치운 적은 세지 않는다(지금의 처치 수와 같다).
- 완료 조건(작업지시 W3-1): `시작 EXP + Σ(칸 수 × 칸 EXP) == Hq.Exp`, `Σ(칸 수 × 칸 Gold) + GoldenCritBonusGold == EarnedGold`.

**W3-2 (A): 판 시작 정보** — `GameSession`에 더한다.

```csharp
public RunStartInfo StartInfo { get; }   // 판을 만들 때 정해진 값

public sealed class RunStartInfo
{
    public int Seed { get; }
    public int GrowthStage { get; }
    public int StartLevel { get; }
    public float TimeLimitSeconds { get; }               // 노드가 반영된 시작 제한 시간
    public IReadOnlyList<SupplyRequest> StartSupply { get; }
}
```

- `GameSession.cs`는 C(W3-1, `CreateRawData` 부분)와 A(W3-2, `StartInfo` 부분)가 함께 고친다. 서로 다른 부분만 고친다.

### 5.4 동기화 DTO (`BlackHole.Net`)

3장의 JSON과 키·타입이 같은 `[Serializable]` 클래스다. `JsonUtility` 규칙(public 필드, camelCase, null 없음)을 따른다.

| DTO | 쓰임 |
|---|---|
| `NodePurchasePayloadDto`, `RunStartPayloadDto`(+`SupplyDto`), `SettlementPayloadDto`(+`KillDto`), `ResetPayloadDto`(필드 없음) | 로그 payload |
| `SyncResponseDto` { `appliedThroughSeq`, `rejected`, `contentVersion`, `progress` } | 응답 |
| `RejectedLogDto` { `seq`, `type`, `code`, `message` } | 응답의 `rejected`. **`code`가 빈 문자열이면 거부 없음**(3.7) |
| `ProgressDto` { `lastAppliedSeq`, `gold`, `growthStage`, `nodes`, `updatedAt` } + `ProgressNodeDto` { `nodeId`, `rank` } | 응답의 `progress`. 통계 전송의 `BlackHole.Analytics.NodeRankDto`와 이름이 겹치지 않게 했다 |

- `LogTypes` 상수: `RunStart = "RUN_START"`, `Settlement = "SETTLEMENT"`, `NodePurchase = "NODE_PURCHASE"`, `Reset = "RESET"`.
- `RejectCodes` 상수: 3.6의 코드 전체.

### 5.5 로그 JSON 만들기 (`SyncJson`, `BlackHole.Net`)

`JsonUtility`는 타입마다 다른 `payload`를 쓰지 못하므로(C12) 봉투는 글자로 합친다.

```csharp
public static class SyncJson
{
    // 로그 하나: {"seq":…,"type":"…","occurredAtUtc":"…","buildVersion":"…","contentVersion":"…","payload":<payloadJson>}
    // 문자열은 JSON 규칙대로 이스케이프한다. payloadJson은 IJsonSerializer.ToJson(payload DTO)의 결과를 그대로 넣는다.
    public static string ComposeLog(long seq, string type, string occurredAtUtc, string buildVersion,
                                    string contentVersion, string payloadJson);

    // 요청 본문: {"logs":[<log1>,<log2>,…]}. 로그 글자를 그대로 이어 붙인다.
    public static string ComposeRequest(IEnumerable<string> logJsons);

    // 요청 본문의 UTF-8 바이트 수(1 MB 한도 계산용).
    public static int Utf8Length(string json);
}
```

- `occurredAtUtc` 형식: `yyyy-MM-ddTHH:mm:ssZ` (UTC, 초 단위).
- 이 클래스는 A0(5장 산출물)에서 구현하고, 3.8의 샘플과 키·타입이 같은지 테스트로 확인한다.

### 5.6 저장·전송 경계 (`BlackHole.Net`)

```csharp
// 보낼 로그 하나. 만들 때 정한 JSON 글자를 바꾸지 않는다.
public sealed class PendingLog
{
    public long Seq { get; }
    public string Type { get; }      // LogTypes
    public string Json { get; }      // SyncJson.ComposeLog의 결과
}

// 진행 저장과 함께 있는 로그 저장소(W3-3, A 구현). 메서드는 모두 메인 스레드에서 부른다.
public interface ISyncStore
{
    // 아직 서버가 반영하지 않은 로그(seq 오름차순).
    IReadOnlyList<PendingLog> Pending { get; }

    // 새 로그가 쌓였을 때. 온라인이면 SyncSender가 이것을 보고 보낸다.
    event Action LogAppended;

    // 응답 하나를 반영한다(한 번의 원자적 저장). 규칙은 5.7.
    SyncApplyOutcome ApplyResponse(SyncResponseDto response);

    // 마지막으로 받은 거부. 없으면 null. 알림(W3-5)이 읽는다.
    RejectedLogDto LastRejection { get; }
    bool LastRejectionShown { get; }
    void MarkLastRejectionShown();
}

public enum SyncApplyOutcome
{
    Applied,            // 거부 없음
    AppliedWithDrift,   // 거부 없음, 남은 로그가 없는데 서버 진행도가 로컬과 달라 서버 값으로 맞춤(규칙 이식 버그 신호, 경고 로그)
    Rejected,           // 거부 있음. 남은 로그 폐기, 서버 진행도로 덮어씀
}

// 동기화 요청 하나(W3-4, C 구현). 토큰은 IAuthSession에서 얻고, 401이면 RefreshAfterUnauthorizedAsync 후 한 번 다시 보낸다.
public interface ISyncApi
{
    Task<SyncCallResult> SendAsync(IReadOnlyList<PendingLog> logs);
}

public sealed class SyncCallResult
{
    public SyncCallOutcome Outcome { get; }
    public SyncResponseDto Response { get; }   // Outcome이 Response일 때만
}

public enum SyncCallOutcome
{
    Response,        // 200, 응답 본문을 읽었다
    Unreachable,     // 네트워크 오류, 타임아웃, 5xx → 이번 실행은 오프라인(Unreachable)
    Unauthorized,    // 갱신 후에도 401 → 오프라인(IAuthSession이 이미 바꿈)
    ProtocolError,   // 400, 413, 형식이 맞지 않는 200 → 오프라인(ProtocolError), 오류 로그
}
```

### 5.7 응답 반영 규칙 (`ApplyResponse`)

요청을 보낸 뒤 응답을 기다리는 동안에도 게임은 계속되어 **새 로그가 쌓일 수 있다.** 그래서 서버 진행도로 덮어쓰는 것은 덮어써도 잃는 것이 없을 때만 한다.

| 경우 | 처리 |
|---|---|
| 거부 없음, `appliedThroughSeq` 뒤에 남은 로그가 **있음** | 반영된 로그(`seq ≤ appliedThroughSeq`)만 지운다. **진행도는 덮어쓰지 않는다**(로컬 = 서버 + 남은 로그) |
| 거부 없음, 남은 로그가 **없음** | 반영된 로그를 지운다. 서버 진행도가 로컬과 다르면 서버 값으로 덮어쓰고 경고 로그(`AppliedWithDrift`) |
| 거부 있음 | 남은 로그를 **모두** 지운다(보낸 뒤 쌓인 것 포함, 계획서 D2). 서버 진행도로 덮어쓴다. 거부를 `LastRejection`에 기록(알림 전) |

공통:

- 다음 seq는 `appliedThroughSeq + 1`로 맞춘다. 단, 남은 로그가 있으면 `마지막 남은 로그의 seq + 1`이다.
- 서버 진행도로 덮어쓸 때는 진행 저장의 "큰 값 유지"(`ProgressSave.Capture`)를 거치지 않는다. 덮어쓴 값이 그대로 저장된다.
- 아직 새 게임·계속을 고르기 전(타이틀)이면, 덮어쓴 값은 "계속"으로 이어 할 저장이 된다.
- 판이 진행 중일 때 덮어쓰게 되면(온라인 중 거부), 판은 그대로 진행하고 결산은 덮어쓴 진행도 위에 더해진다. 그 결산 로그는 열린 판이 서버와 맞지 않아 다시 거부될 수 있다. 드문 경우라 수용한다.

### 5.8 전송 규칙 (`SyncSender`, W3-4)

- **앱 시작**: `IAuthSession`이 `Online`이 되면 쌓인 로그를 전부 보낸다(없으면 빈 배열 하나). 이 동기화가 끝나거나(응답, 실패) `syncTimeoutSeconds`가 지나면 시작 버튼을 푼다(2.8). `Offline`이면 보내지 않고 바로 푼다.
- **온라인 중**: `LogAppended`마다 보낸다. 요청은 **동시에 하나만** 보낸다. 보내는 중에 쌓인 로그는 응답을 반영한 뒤 이어서 보낸다.
- **나눠 보내기**(3.5): 한 요청에 최대 500개, 본문 1 MB(`SyncJson.Utf8Length`) 이하. 앞 요청의 응답을 반영한 뒤 다음 요청을 보낸다. 거부가 나오면 멈춘다(남은 로그는 `ApplyResponse`가 이미 지웠다).
- **실패**: `Unreachable`, `Unauthorized`, `ProtocolError`면 로그를 그대로 두고 이번 실행은 더 보내지 않는다(계획서 D9). 오프라인 사유는 `IAuthSession.GoOffline`으로 알린다.
- **`CONTENT_VERSION_MISMATCH`**: 지금은 다른 거부와 똑같이 처리한다(3.6, `ContentOutdated` 전환은 추후).

### 5.9 거부 알림 (W3-5)

- `ISyncStore.LastRejection`이 있고 아직 보여 주지 않았으면, 타이틀 또는 업그레이드 화면에 들어갈 때 한 번 보여 주고 `MarkLastRejectionShown()`을 부른다.
- 문구: **"서버에서 일부 기록을 받아들이지 않아 진행도를 서버 기준으로 맞췄습니다. ({종류}, {code})"**
  - 종류: `RUN_START` 판 시작, `SETTLEMENT` 판 결산, `NODE_PURCHASE` 노드 구매, `RESET` 새 게임, 그 밖에는 기록.
- 확인 버튼 하나짜리 기존 확인 창(`ConfirmPanel`)이나 작은 텍스트 중 화면에 맞는 것을 쓴다.

### 5.10 저장 포맷 2 (W3-3, A)

진행 저장(`progress.json`)에 동기화 상태를 더해, 진행도와 로그가 **한 파일, 한 번의 원자적 쓰기**로 저장되게 한다.

```json
{
  "FormatVersion": 2,
  "Gold": 18, "GrowthStage": 0, "Nodes": [ { "NodeId": "timer-01", "Rank": 1 } ], "SavedAtUtc": "…",
  "Sync": {
    "NextSeq": 4,
    "Pending": [ { "Seq": 3, "Type": "NODE_PURCHASE", "Json": "{\"seq\":3,…}" } ],
    "LastRejection": { "Seq": 0, "Type": "", "Code": "", "Message": "", "AtUtc": "", "Shown": true }
  }
}
```

- 포맷 1 파일을 읽으면 `Sync`는 처음 상태(`NextSeq` 1, 로그 없음)다.
- 로그 하나를 쌓는 것은 "진행도 반영 + 로그 추가 + 저장"을 한 번에 한다. 진행도만 저장되고 로그가 빠지는 일이 없다.
- 테스트 세션(테스트 도구가 진행도를 바꾼 실행)은 지금처럼 저장하지 않고, 로그도 만들지 않는다.

### 5.11 수치와 문구

| 항목 | 값 | 비고 |
|---|---|---|
| 로그인 전체 / 요청 / 동기화 요청 타임아웃 | 8 / 5 / 10초 | 2장 C11 |
| 요청당 로그 수 / 본문 | 500개 / 1 MB | 3장 C14 |
| 판 시작 제한 시간 비교 | 0.001초 | 4장 C22 |
| 결산 시간 검사(S8) 허용 오차 | W1-6에서 결산 검증과 함께 정한다 | 서버 내부 값이라 클라이언트에 영향 없음 |
| 거부 알림 문구 | 5.9 | |

### 5.12 결정 사항 (5장, 확정)

| # | 결정 | 이유 |
|---|---|---|
| C23 | 로그 생성기(`SyncLogFactory`)는 Unity 밖의 새 어셈블리 `BlackHole.Sync`에 둔다 | 시뮬레이션 도구(net8.0)가 같은 코드로 캠페인 로그를 만든다(W4-2a) |
| C24 | 진행도와 대기 로그를 `progress.json` 한 파일에 함께 저장한다(포맷 2) | 한 번의 원자적 쓰기로 둘이 어긋나지 않는다 |
| C25 | 거부가 없으면 남은 로그가 있는 동안 서버 진행도로 덮어쓰지 않는다 | 응답을 기다리는 동안 생긴 로그의 효과를 잃지 않는다 |
| C26 | 전송은 동시에 하나만, 나눈 요청은 앞 응답을 반영한 뒤 다음을 보낸다 | seq 순서와 거부 후 멈춤을 단순하게 보장한다 |
| C27 | 결산 시간 검사(S8)의 허용 오차는 W1-6에서 정한다 | 서버 내부 값이고 결산 검증과 함께 보정해야 한다 |

### 5.13 산출물

클라이언트 저장소 `feat/logContract` 브랜치(`feat/serverConnection`에서 분기).

| 산출물 | 위치 | 상태 |
|---|---|---|
| 동기화 DTO(5.4), `LogTypes`·`RejectCodes`·`SyncLimits` | `Assets/Scripts/Net/SyncDtos.cs`, `SyncCodes.cs` | 완료 |
| `SyncJson`(5.5) | `Assets/Scripts/Net/SyncJson.cs` | **구현 완료** |
| `PendingLog`, `ISyncStore`, `SyncApplyOutcome`, `ISyncApi`, `SyncCallResult`(5.6) | `Assets/Scripts/Net/` | 완료(구현 없음) |
| `KillBucket`, `KillBucketCount`(5.3) | `Assets/Scripts/Core/Enemies/KillBucket.cs` | 타입 완료. 세는 일과 `BattleRawData` 속성은 W3-1 |
| `RunStartInfo`(5.3) | `Assets/Scripts/Core/Battle/Session/RunStartInfo.cs` | 타입 완료. `GameSession.StartInfo` 연결은 W3-2 |
| `BlackHole.Sync` 어셈블리, `SyncLogFactory` | `Assets/Scripts/Sync/` | 구매·새 게임 로그 완료, 판 시작·결산은 W3-2 |
| 동기화 샘플(3.8) | `Assets/Tests/Net/SyncSamples/` | 서버와 같은 파일 |
| 테스트 | `Assets/Tests/Net/SyncJsonTests.cs`(9개) | 통과. 구매·새 게임 로그가 샘플과 글자까지 같고, 응답 샘플을 DTO로 읽는다 |

- 응답의 산 노드 DTO 이름은 `ProgressNodeDto`다(통계 전송의 `BlackHole.Analytics.NodeRankDto`와 겹치지 않게).
