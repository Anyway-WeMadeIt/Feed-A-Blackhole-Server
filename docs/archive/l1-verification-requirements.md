# L1 검증이 서버에 요구하는 정보

- 상태: **분석 문서.** 클라이언트가 보내는 `battle`, `purchase` 엔트리 중 서버가 무엇을 검증할 수 있고, 그러려면 서버가 **미리** 무엇을 가지고 있어야 하는지 정리한다. 작업 계획이 필요한 정보를 빠짐없이 만드는지 점검하는 것이 목적이다.
- 관련 문서: 작업 정의 [offline-sync-work-breakdown.md](offline-sync-work-breakdown.md)(C2, C3, C4a, C4b), 계약 [api-spec.md](../api-spec.md), 콘텐츠 계약 [battle-content-contract.md](battle-content-contract.md).
- 코드 근거는 클라이언트 `GameSessionFactory`, `EnemyDefinition.StatsAt`, `SizeRule`, `MassRule`, `DeathRewards`, `Hq`, `HqGrowthDefinition`, `HqMilestone`, `TimeLimitDefinition`, `EnemyUpgradeStats`, `HqUpgradeStats`다(2026-10-12 시점).

## 1. 한눈에 보기

**원칙:** 서버는 클라이언트가 보낸 **파생값을 믿지 않고**, 서버가 가진 데이터로 같은 값을 다시 계산해 대조한다. 클라이언트가 보낸 값 중 서버가 그대로 상태에 쓰는 것은 없다(Gold와 성장도의 변화는 모두 서버가 계산한 결과다).

**검증에 들어가는 입력은 세 가지다.**

| 입력 | 내용 | 출처 |
|---|---|---|
| ① 클라이언트가 보낸 엔트리 | `battle`(시작 스냅샷, 결산, 처치 장부), `purchase`, `reset` | 요청 |
| ② 서버가 가진 정적 데이터 | 전투 콘텐츠, 노드 콘텐츠 | 서버 저장소의 파일 |
| ③ 서버가 가진 동적 데이터 | 계정 상태(Gold, 성장도, 노드 Rank), 시간 정보, 서버 시계 | DB, `Clock` |

**파생 계산의 흐름** (왼쪽이 입력, 오른쪽이 만들어지는 값)

```
계정의 노드 Rank + 노드 콘텐츠 ──C2──▶ 스탯 표 (60개 수치)
스탯 표 + 전투 콘텐츠 ──C4a──▶ 시작 공급, 레벨업당 성장 공급
스탯 표 + 전투 콘텐츠 ──C4b──▶ 색 분포 허용 범위, 크기, 황금 수치와 확률, 재생성 확률, 시간 추가 확률
전투 콘텐츠(성장) + 처치 장부 ──▶ EXP, Level
위의 모든 값 + 처치 장부 + 계정 상태 ──C3──▶ 검증 결과와 새 상태
```

## 2. 클라이언트가 보내는 값별 검증

### 2.1 `battle` 엔트리

| 보내는 값 | 무엇을 검증하나 | 정확도 | 서버가 필요한 정보 |
|---|---|---|---|
| `contentVersion` | 서버의 번들 해시와 같다 | 정확 | 번들 해시(A2) |
| `battleId`, `seed`, `stats` | **검증하지 않는다**(진단용) | – | – |
| `start.growthStage` | 계정의 성장도와 같다 | 정확 | 계정 상태 |
| `start.upgrades`(60개) | 서버가 계산한 스탯 표와 같다 | 정확(float 허용 오차 `1e-4`) | 노드 콘텐츠, 계정의 노드 Rank |
| `start.supply` | 서버가 계산한 시작 공급과 같다 | 정확 | 전투 콘텐츠(시작 공급, 변환 대상), 스탯 표 |
| `result.kills[].kind, tier, size, golden` | 콘텐츠와 이 판의 구성이 허용하는 값인가 | 정확(집합) | 종류별 Tier 수, 질량에서 나오는 색 비율, 크기 상한, 황금 성질과 확률 |
| `result.kills[].count` | 공급 상한 안인가(종류, Tier, 크기, 황금별) | **상한**(통계 여유) | 시작 공급, 성장 공급, 재생성 확률, Level, 색 비율, 크기, 황금 확률 |
| `result.kills[].goldenCrits` | 황금 처치 수 이하이고 확률 상한 안인가 | **상한**(통계 여유) | 황금 치명 확률 |
| `result.earnedGold` | `(종류, Tier, 크기, 황금)`별 처치 수로 다시 계산한 합과 같다 | 정확(황금 처치당 ±1) | Tier 기본 Gold, 크기 규칙, 황금 배율과 치명 보상 배율 |
| `result.exp` | 처치 수로 다시 계산한 합과 같다 | 정확 | Tier 기본 EXP, 크기 규칙, 시작 Level의 누적 EXP |
| `result.level` | EXP를 사다리에 대 본 Level과 같다(목표 Level에서 멈춤) | 정확 | EXP 사다리, 이정표 |
| `result.ended` | `level`과 일관된가(`Milestone` ⇔ 목표 Level 도달) | 정확 | 이정표 |
| `result.settledGold` | 결산 규칙으로 다시 계산한 값과 같다 | 정확 | 이정표의 목표 잔액, 계정의 현재 Gold |
| `result.growthStageAfter` | 결산 규칙으로 다시 계산한 값과 같다 | 정확 | 계정의 성장도 |
| `result.elapsed` | 제한 시간 상한 안인가, 누적 시간이 서버 시계를 넘지 않는가 | **상한** | 기본 제한 시간, `timer`·`growth.time` 수치, 시간 추가 확률과 초, 계정의 시간 정보, 서버 시계 |

### 2.2 그 밖의 엔트리

| 엔트리 | 검증 | 서버가 필요한 정보 |
|---|---|---|
| `purchase`(`nodeId`, `rank`) | 노드 존재, 해금(드러남), 최대 Rank, `rank`가 현재 + 1, Gold 충분 | 노드 트리(배치, 선, 시작 노드), Rank별 비용, 계정의 Gold와 노드 Rank |
| `reset` | 없음(항상 유효) | – |
| `claimed`(요청 필드) | 서버가 계산한 상태와 비교(진단용, 거절하지 않음) | 계정 상태 |

## 3. 검사별 상세

표기: 종류 `k`, Tier `t`, 크기 `s`, 황금 `g`, 처치 수 `n(k,t,s,g)`, 황금 보너스 치명 수 `c(k,t,s,g)`, `L = level − 시작 Level`(이 판에서 오른 Level 수).

서버가 파생 계산에서 쓰는 값: 스탯 표 `ST`(`value(statId)`, `gain(statId) = value − 기본값`), 시작 공급 `start[k]`, 레벨업당 성장 공급 `growth[k]`, 색 비율 `ratio[k][t]`, 크기 상한 `sizeMax[k]`, 황금 확률 `goldChance[k]`와 효과, 재생성 확률 `respawn[k]`, 시간 추가 확률 `timeChance[k]`.

### ① 형식 → `MALFORMED_ENTRY`

종류, Tier(`0 … 색 수-1`), 크기(`1 … sizeMax[k]`)가 범위 안, `count ≥ 0`, `goldenCrits`는 황금 항목에만 있고 `count` 이하, 같은 `(kind, tier, size, golden)` 키가 두 번 나오지 않음, 혜성은 Tier 0, 크기 1, 황금 아님.
**필요한 정보:** 종류별 Tier 수(A2), 크기 상한(C4b), 황금 성질 유무(A2).

### ② 콘텐츠 버전 → `CONTENT_VERSION_MISMATCH`

`contentVersion == 서버 번들 해시`. **필요한 정보:** 번들 해시(A2).

### ③ 시작 스냅샷 → `BATTLE_START_MISMATCH`

- `start.growthStage == 계정 성장도`.
- `start.upgrades`의 키 집합이 서버 스탯 표와 같고 값이 같다(정수 수치는 정확히, 실수는 상대 오차 `1e-4`).
- `start.supply == C4a의 시작 공급`(종류별 수량, 변환 반영).

**필요한 정보:** 계정의 성장도와 노드 Rank, 노드 콘텐츠(수치 정의의 기본값·최소·최대, Rank별 효과), 전투 콘텐츠의 시작 공급과 변환 대상.

**주의:** 서버는 이 판의 **현재 작업 상태**(같은 묶음 앞쪽 엔트리까지 적용된 상태)로 스탯 표를 계산한다. 같은 묶음에서 먼저 일어난 구매가 반영되어야 한다.

### ④ 장부 → `BATTLE_IMPLAUSIBLE`

한 처치의 Gold와 EXP는 클라이언트 코드(`EnemyDefinition.StatsAt`, `DeathRewards`)와 같게 계산한다.

```
G0   = Tier.Gold × s                    (크기 s는 Gold와 EXP에 s배)
G    = g ? round(G0 × 황금 배율) : G0   (half away from zero)
보너스 = round(G × 황금 치명 보상 배율)   (치명 하나당)
earnedGold = Σ n × G + Σ c × 보너스
exp        = 시작 Level의 누적 EXP + Σ n × (Tier.Exp × s)
```

- 황금 배율 = 콘텐츠의 `Multiplier` + `gain(goldenAsteroid.rewardScale)/100`, 치명 보상 배율 = 콘텐츠의 `CritRewardScale` + `gain(goldenAsteroid.critRewardScale)/100`(C4b).
- `earnedGold`의 허용 오차는 **황금 처치당 ±1, 치명당 ±1**(황금 배율의 float 반올림). 그 밖의 항은 정수라 정확히 같아야 한다.
- `exp`는 정수라 정확히 같다.
- 시작 Level의 누적 EXP는 `expToReach(startLevelAt(성장도))`다(판은 그 Level의 임계값에서 시작한다).

### ⑤ Level과 결산 → `BATTLE_IMPLAUSIBLE`

- `level == growth.levelFor(exp, 성장도)`: `exp`가 다음 Level의 누적 EXP 이상인 동안 Level을 올리되 **목표 Level(다음 이정표의 Level)에서 멈춘다.**
- `ended == Milestone` ⇔ 목표 Level이 있고 `level == 목표 Level`. `TimeUp`, `Manual`인데 목표 Level에 도달해 있으면 위반이다(목표에 닿으면 그 Step에서 판이 끝난다).
- `Milestone`이면 `settledGold == max(0, 이정표 목표 잔액 − 계정 Gold)`(그 판에서 번 Gold는 버린다)이고 `growthStageAfter == 성장도 + 1`. 아니면 `settledGold == earnedGold`, `growthStageAfter == 성장도`.
- 반영: `Gold += settledGold`, `성장도 = growthStageAfter`.

**필요한 정보:** EXP 사다리(`LevelExp`), 이정표(`Level`, `TargetGold`), 계정의 현재 Gold.

### ⑥ 공급 상한 → `BATTLE_IMPLAUSIBLE`

**처치 수가 생성된 적의 수를 넘을 수 없다**는 사실로 처치 장부 전체를 묶는다. 이 검사가 없으면 "소행성 100만 마리를 잡았다"는 주장을 막을 방법이 없다.

일반 종류(픽업이 아닌) `k`마다:

```
base[k]       = start[k] + L × growth[k]          (시작 공급 + 오른 Level마다의 성장 공급)
slack[k]      = a·√base[k] + b                      (재생성의 통계 여유, 정책 상수)
spawnedMax[k] = ceil((base[k] + slack[k]) ÷ (1 − respawn[k]))      ( respawn[k] < 0.99 )
```

- **왜 `1 ÷ (1 − p)`인가:** 적이 죽을 때마다 확률 `p`로 같은 종류가 한 마리 더 생긴다. 생성 수 = `base + 재생성 수`이고 처치 수는 생성 수 이하이므로 `처치 ≤ base + p·처치 + 여유`, 곧 `처치 ≤ (base + 여유) ÷ (1 − p)`다.
- **검사 1(종류 합계):** `Σ n(k,·,·,·) ≤ spawnedMax[k]`.
- **검사 2~4는 "주변분포"별로 따로 본다.** Tier, 크기, 성질은 **서로 독립인 선택기**가 정한다(`QuotaPicker`가 각자 비율에 가깝게 배분한다). 그래서 결합 분포는 제약이 없고 각 분포만 제약이 있다.
  - Tier: `Σ n(k,t,·,·) ≤ ceil(spawnedMax[k] × ratio[k][t]) + 2`이고 `ratio[k][t] == 0`이면 0.
  - 크기: `Σ n(k,·,s,·) ≤ ceil(spawnedMax[k] ÷ sizeMax[k]) + 2`(크기 1부터 `sizeMax`까지가 같은 몫).
  - 황금: `Σ n(k,·,·,true) ≤ ceil(spawnedMax[k] × goldChance[k]) + 2`이고 `goldChance[k] == 0`이면 0.
- **`respawn[k] ≥ 0.99`이면** 위 상한이 무한에 가까워져 쓸 수 없다. 이때는 종류별 상한을 건너뛰고 총 처치 수를 `초당 최대 처치 수(정책 상수) × elapsed`로 묶는다.
- **가정:** `QuotaPicker`의 근사(각 칸의 개수가 `N × 비율`에서 ±1 안), 재생성이 독립 시행. 이 가정이 틀리면 정상 판이 거절되므로 골든 케이스로 확인한다.

**필요한 정보:** 시작 공급과 성장 공급(C4a), 재생성 확률·색 비율·크기 상한·황금 확률(C4b), EXP 사다리(Level 계산), 정책 상수.

### ⑦ 시간 상한 → `BATTLE_IMPLAUSIBLE`

```
limit = 판 규칙의 TimeLimit + gain(timer) + L × gain(growth.time)
        + 시간 추가 최대 횟수 × KillTimeBonus + 프레임 여유
시간 추가 최대 횟수 = Σ_k ceil(K[k] × timeChance[k] + c·√(K[k]·timeChance[k]) + 2)     (K[k] = 일반 종류 k의 처치 수)
elapsed ≤ limit
```

- **주의:** 기본 시간은 `TimeLimit(콘텐츠)` + `gain(timer)`(늘어난 양)이다. `timer` 값 자체(기본값 12 + 합)를 더하면 기본 시간이 두 번 들어간다(`TimeLimitDefinition.Upgraded`).
- 프레임 여유는 한 Step의 길이(최대 0.333초)에서 온다.

**필요한 정보:** 판 규칙(`TimeLimit`, `KillTimeBonus`), 스탯 표(`timer`, `growth.time`), 시간 추가 확률(C4b), `L`(Level).

### ⑧ 누적 시간 → `BATTLE_IMPLAUSIBLE`

한 계정이 판에 쓴 시간의 합은 실제로 흐른 시간을 넘을 수 없다. **서버 시계**로 본다(클라이언트가 보낸 시각은 쓰지 않는다).

**현재 계획(`last_sync_at` 기준)에는 결함이 있다**([7장](#7-계획-점검)): 묶음이 둘 이상으로 나뉘면 뒤 묶음이 거절된다. 권장 방식은 계정 수명 기준이다:

```
battle_elapsed_total + (이 묶음에서 지금까지의 elapsed 합) ≤ (서버 현재 시각 − 계정 생성 시각) + 여유(60초)
```

**필요한 정보:** 계정 생성 시각(이미 `account`에 있음), 계정의 누적 전투 시간(`battle_elapsed_total`, 새 컬럼), 서버 시계.

### ⑨ 혜성 → `BATTLE_IMPLAUSIBLE`

```
혜성 처치 수 ≤ floor(elapsed ÷ 출현 주기) × (rainChance > 0 ? RainCount : 1) + 1
gain(comet.spawnChance) == 0 이면 혜성 처치 수 == 0
```

**필요한 정보:** 혜성의 `SpawnPeriod`, `RainCount`(A2), 스탯 표의 `comet.spawnChance`, `comet.rainChance`.

### ⑩ 황금 치명 상한 → `BATTLE_IMPLAUSIBLE`

`goldenCrits`는 황금 보너스 치명타의 횟수이고 그만큼 Gold가 늘어난다(황금 처치마다 `치명 보상 배율`배). "황금 처치가 전부 치명"이라는 주장을 막는다.

```
Σ c ≤ ceil(황금 처치 수 × 황금 치명 확률 + c'·√(황금 처치 수 × 확률) + 2)
황금 치명 확률 == 0 이면 Σ c == 0
```

**필요한 정보:** 황금 치명 확률 = `min(1, 콘텐츠의 CritChance + gain(goldenAsteroid.critChance)/100)`(C4b).

### 구매 검증(`purchase`)

클라이언트 `NodePurchase`와 같은 판정이다. 검사 순서는 `UNKNOWN_NODE`(트리에 없음) → `MAX_RANK_REACHED` → `NODE_LOCKED`(드러나지 않음) → `RANK_MISMATCH` → `NOT_ENOUGH_GOLD`. 드러남은 "시작 노드이거나 선으로 이어진 노드 중 Rank 1 이상인 것이 있음"이다(서버 `NodeGraph.isRevealed`). 적용은 Gold에서 그 Rank의 비용을 빼고 Rank를 올린다.

## 4. 서버가 미리 가져야 할 정보

### 4.1 정적 데이터

| 데이터 | 구체적인 항목 | 쓰는 검사 | 제공하는 작업 |
|---|---|---|---|
| **전투 콘텐츠** | `BattleRules`(`TimeLimit`, `KillTimeBonus`) | ⑦ | A2 |
| | 종류별 Tier 표(`Gold`, `Exp`), Tier 수 | ①④⑤⑥ | A2 |
| | 황금 성질의 효과(`Multiplier`, `CritChance`, `CritRewardScale`) | ④⑥⑩ | A2 |
| | 종류별 `UpgradesTo`, `SpawnPeriod`, `RainCount` | ③⑨, C4a | A2 |
| | 시작 공급 목록(순서 포함) | ③⑥ | A2 |
| | EXP 사다리, 이정표(`Level`, `TargetGold`) | ④⑤⑥ | A2 |
| **노드 콘텐츠** | 수치 정의 60개(기본값, 최소, 최대, 정수 여부) | ③ 이하 모두 | 기존(`NodeContent`) |
| | 노드와 Rank별 비용, 효과 | ③, 구매 | 기존 |
| | 배치된 노드, 선, 시작 노드 | 구매 | 기존(`NodeTree`, `NodeGraph`) |

### 4.2 동적 데이터

| 데이터 | 쓰는 검사 | 비고 |
|---|---|---|
| 계정의 Gold, 성장도, 노드 Rank(산 순서) | ③⑤, 구매 | 같은 묶음의 앞 엔트리까지 적용된 **작업 상태** |
| 계정 생성 시각 | ⑧ | `account`에 이미 있음 |
| **누적 전투 시간**(`battle_elapsed_total`) | ⑧ | **새 컬럼**(권장) |
| 서버 시계 | ⑧ | 이미 `Clock` 빈이 있음 |
| 마지막 동기화 응답 | 멱등 | B2 |

### 4.3 서버에 구현할 규칙 (코드)

| 규칙 | 내용 | 클라이언트 근거 | 작업 |
|---|---|---|---|
| 스탯 표 | Rank별 효과의 합 → 기본값 + 합을 최소·최대로 자름 | `NodePurchase.StatsFor` | C2 |
| 늘어난 양과 값 | 질량만 값 자체(`ValueOf`), 나머지는 늘어난 양(`GainOf`) | `EnemyComposition.From` | C2, C4a, C4b |
| 시작 공급 | 콘텐츠 목록 + `asteroid.count` 보너스 + 변환 사슬 | `StartSupplyOf` | C4a |
| 성장 공급 | 변환 반영 시작 수 × `growth.*` % 반올림 | `GrowthSupplyOf` | C4a |
| 색 비율 | 질량 구간 `[m−2, m]`과 색 칸의 겹침 ÷ 2 | `MassRule` | C4b |
| 크기 | `1 + gain(*.size)` 반올림, 상한 20, Gold와 EXP에 선형 배율 | `SizeRule` | C4b |
| 황금 수치 | 배율, 치명 확률(상한 1), 치명 보상 배율 | `EnemyComposition.UpgradedEffect` | C4b |
| 재생성, 시간 추가 확률 | `min(1, gain/100)` | `EnemyComposition.From` | C4b |
| Level 계산 | EXP 사다리, 목표 Level에서 정지 | `Hq` | A2(`levelFor`) |
| 이정표 보상 | `max(0, 목표 잔액 − Gold)` | `HqMilestone.RewardFor` | A2 |
| 반올림 | half away from zero | `Math.Round(..., AwayFromZero)` | 공통 |

### 4.4 L1에 필요한 수치(스탯)는 60개 중 26개

스탯 표의 **동등 비교(③)** 는 60개 전부를 보지만, 파생 계산에 **들어가는** 수치는 26개다. 나머지 34개(브레이커, 번개, 레이저, 슈퍼노바, 달, 혜성의 지속 시간과 치명 보너스)는 L1에서는 비교에만 쓰이고 계산에는 쓰이지 않는다.

| 쓰임 | 수치 |
|---|---|
| 제한 시간 | `timer`, `growth.time` |
| 성장 공급 | `growth.asteroids`, `growth.planets`, `growth.stars` |
| 시작 공급과 변환 | `asteroid.count`, `asteroid.toPlanet`, `planet.toStar` |
| 색 분포 | `asteroid.massScale`, `planet.massScale`, `star.massScale` |
| 크기 | `asteroid.size`, `planet.size`, `star.size` |
| 재생성 | `asteroid.respawnChance`, `planet.respawnChance`, `star.respawnChance` |
| 시간 추가 | `asteroid.timeChance`, `planet.timeChance`, `star.timeChance` |
| 황금 | `goldenAsteroid.spawnChance`, `.rewardScale`, `.critChance`, `.critRewardScale` |
| 혜성 | `comet.spawnChance`, `comet.rainChance` |

### 4.5 서버가 필요로 하지 않는 정보

브레이커 수치, 위치와 이동(`MoveSpeed`, `Radius`, `RadiusStep`, 출현 띠), `MaxHealth`(콘텐츠 검증에만), 황금 외 사망 효과의 수치(번개, 레이저, 폭발), `FieldScale`, 노드의 화면 좌표, 표현 데이터. 이들은 콘텐츠 파일에 있어서 해시에는 들어가지만 서버가 계산에 쓰지 않는다.

## 5. 검증하지 못하는 것과 조작 시나리오

| 조작 | L1에서 | 설명 |
|---|---|---|
| `earnedGold`를 부풀림 | **잡는다** | 장부(④)로 다시 계산한다 |
| 높은 Tier의 처치를 주장 | **잡는다** | 질량이 허용하지 않는 Tier는 0, 허용하는 Tier도 비율 상한(⑥)까지 |
| 처치 수를 크게 부풀림 | **잡는다** | 공급 상한(⑥) |
| "황금 처치가 전부 치명"이라고 주장 | **잡는다** | ⑩ |
| 업그레이드 표나 시작 공급을 속임 | **잡는다** | ③. 서버는 자기 표로 계산하므로 속여도 상한이 늘지 않는다 |
| 이정표 판정을 위조 | **잡는다** | `level`은 EXP에서 도출되고 EXP는 처치 장부에서 나온다(⑤) |
| `elapsed`를 길게 속임 | **잡는다** | ⑦, ⑧ |
| `elapsed`를 짧게 속임 | 의미 없다 | 보상은 처치에 달려 있고 시간에 달려 있지 않다 |
| 같은 엔트리를 다시 제출 | **잡는다** | `baseRevision`과 `batchId`(동기화 판정) |
| **공급 상한 안에서 처치 속도를 부풀림** | 못 잡는다 | "공급된 적을 전부 잡았다"는 정직한 최대와 구분되지 않는다. 이벤트가 있어야 한다(L3) |
| 자동 플레이(봇)로 정상적인 최대치를 반복 | 못 잡는다 | 입력을 보지 않는다 |
| 유리한 시드를 고름 | 못 잡는다 | 시드를 검증하지 않는다 |

**L1이 허용하는 최대 이득** = "공급된 적을 전부, 색 분포가 허용하는 가장 높은 Tier로 잡은 판"이다. 이 값은 정직한 플레이의 상한과 같다. 따라서 L1은 **상한을 넘는 조작을 모두 막고**, 상한 안의 조작은 정직한 최대와 구분하지 못한다.

## 6. 정보 공급 경로의 완결성

위 4장의 모든 정보는 작업 계획 안에 제공 경로가 있다.

| 정보 | 제공 경로 |
|---|---|
| 전투 콘텐츠 | A1(내보내기) → A2(로더) |
| 노드 콘텐츠 | 기존 `NodeContent`, `NodeTree` |
| 스탯 표 | C2 |
| 공급 계산 | C4a |
| 허용 범위 | C4b |
| 계정 상태, 시계 | 기존 + B2의 작업 상태 객체 |
| 누적 전투 시간 | **B2의 DB 마이그레이션(현재 `last_sync_at`만 계획됨)** ← 7장 |
| 클라이언트의 값 | C1, B4, B5 |

서버가 갖지 못해서 **검증이 불가능한 정보**는 L1 범위에서 없다.

## 7. 계획 점검

계획은 L1에 필요한 정보를 **모두 제공하는 구조**이고 범위도 적절하다. 다만 아래 보완이 필요하다. 번호는 중요도 순이다.

### 보완이 필요한 점

| # | 문제 | 보완 | 영향 |
|---|---|---|---|
| 1 | **누적 시간 검사가 묶음 분할에서 오탐을 낸다.** 현재 계획은 "마지막 동기화 이후 `elapsed`의 합 ≤ 서버 시각 − `last_sync_at`"인데, 오프라인 로그가 50개를 넘어 여러 묶음으로 나뉘면 첫 묶음이 `last_sync_at`을 현재로 갱신해서 **뒤 묶음의 판들이(몇 시간 전에 플레이한 것인데도) 거절**된다 | **계정 수명 기준으로 바꾼다:** `battle_elapsed_total + Σ elapsed ≤ (지금 − 계정 생성 시각) + 60초`. 새 컬럼 `player_progress.battle_elapsed_total`을 B2의 마이그레이션에 넣는다. `last_sync_at`은 필요하지 않다. 분할되어도 합계가 같고, 오래 쉬었다 한꺼번에 올려도 실제 시간을 넘지 않는다 | B2, C3(#6), api-spec 판정 규칙 8 |
| 2 | **`goldenCrits`가 `count` 이하라는 것만 본다.** 황금 처치가 전부 치명이라고 주장하면 Gold가 `1 + 치명 보상 배율`배가 된다 | **황금 치명 상한(⑩)을 추가한다** | C3, C4b(치명 확률 제공) |
| 3 | **공급 상한의 "주변분포"가 모호하다.** 현재 표현("Tier, 크기, 황금별 처치 수는 … 안이어야 한다")은 결합 분포로 읽힐 수 있다. 선택기가 서로 독립이라 결합 분포는 제약이 없고, 결합으로 읽으면 정상 판이 거절된다 | 검사를 **주변분포별로** 한다고 명시한다(⑥) | C3 |
| 4 | **`ended` 일관성 검사가 빠져 있다.** `TimeUp`이나 `Manual`인데 목표 Level에 닿은 판, `Milestone`인데 목표가 없는 성장도 | ⑤의 검사를 추가한다 | C3 |
| 5 | **혜성 처치의 형식 검사가 없다.** 혜성은 Tier 0, 크기 1, 황금 아님이고 `comet.spawnChance == 0`이면 0이어야 한다 | ①과 ⑨에 넣는다 | C3 |
| 6 | **`Milestone`이면 `earnedGold`는 정산에 쓰이지 않는다**(번 Gold는 버려진다). 그래도 장부는 맞아야 하는가 | 결정이 필요하다. 권장: **검증하되** 불일치는 다른 장부 불일치와 같이 거절한다(클라이언트 버그나 조작의 신호이고 오탐 위험은 같다) | C3 |

### 오탐 보정을 위해 추가를 권하는 것

| # | 제안 | 이유 | 비용 |
|---|---|---|---|
| 7 | **상한의 사용률 리포트:** 골든 케이스 전체에서 각 상한의 `관측값 ÷ 상한`의 최댓값을 출력하고, **최댓값이 60% 이하가 되도록** 여유 계수를 정한다 | "넉넉하게"가 아니라 근거 있는 값을 얻는다. 정상 판이 거절되는 것이 가장 큰 위험이다 | C3 약 0.5일(테스트 도구) |
| 8 | **진단 필드:** `battle` 엔트리에 선택적 `diagnostics`(종류별 생성 수, 재생성 수, 시간 추가 횟수)를 싣는다. **검증에는 쓰지 않고** 서버 로그와 보정에만 쓴다 | 상한이 실제 판에서 얼마나 여유가 있는지 관측할 수 있다 | C1 약 0.25일 |
| 9 | **C0의 범위를 26개 수치로 한정:** 대응표와 fixture는 L1에 계산으로 쓰이는 26개를 우선하고 나머지 34개는 동등 비교만 한다 | C0의 작업량이 줄고 서버 구현자가 혼란스럽지 않다 | 절감 |

### 영향 요약

- 작업량 변화는 작다(C3 +0.5일, C1 +0.25일, B2의 컬럼 하나). 1번은 **버그 수정**이라 반드시 반영해야 한다.
- 1번, 2번은 **작업 문서(C3, B2)와 [api-spec.md](../api-spec.md)(판정 규칙 8, 서버가 마지막 동기화 시각을 저장한다는 문장)를 고쳐야** 한다.
