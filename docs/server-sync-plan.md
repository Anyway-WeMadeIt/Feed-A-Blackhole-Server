# 서버 연동 작업 계획 (초안)

- 상태: **확정** (2026-10-12). 클라이언트 대규모 변경 이후 기존 문서를 참고하지 않고 현재 코드(클라이언트 `dev` @ 54ae1ab, 서버 `dev` @ c67dc8f)를 기준으로 다시 세운 계획이다.
- 담당과 순서는 [server-sync-assignment.md](server-sync-assignment.md)(작업지시), 구현 형식과 시그니처는 [server-sync-contracts.md](server-sync-contracts.md)(계약), 통신 계약은 [api-spec.md](api-spec.md)에 있다. **이 문서와 계약·API 명세가 다르면 계약·API 명세가 우선한다.**
- 영역 표기: **C**(클라이언트, Unity) / **S**(서버, Spring Boot) / **C+S**. 규모: S 약 1일, M 2~3일, L 4~5일.

## 1. 핵심 요구사항

1. **게스트 로그인**: 게임 실행 시 별도 조작 없이 백그라운드에서 로그인한다. 정해진 시간 안에 실패하면 오프라인 모드로 실행한다.
2. **전투 검증은 L1까지**: 결산 결과가 최소한의 합리성 범위 안에 있는지, 즉 **결산 값들이 서로 일관적인지** 검증한다.
3. **로그 기록**: 클라이언트는 판 시작, 결산, 노드 구매, 새 게임을 로그로 남긴다. 온라인이면 즉시 전송하고, 오프라인이면 쌓아 두었다가 연결되면 한 번에 전송한다.
4. **순차 적용**: 서버는 DB의 진행도를 기준으로 로그를 하나씩 적용한다. 잘못된 로그가 있으면 **그 직전 로그까지만** 반영한다. 어떤 경우든 응답으로 현재 진행도를 돌려준다.
5. **콘텐츠 원본은 클라이언트 저장소**: 전투·노드 데이터는 클라이언트에서 편집한다. 서버는 버전마다 클라이언트에서 내보낸 데이터를 받아온다. 한 버전 안에서 콘텐츠는 불변이다.

## 2. 현재 상태 (코드 기준)

### 2.1 클라이언트

| 항목 | 위치 | 내용 |
|---|---|---|
| 순수 C# 코어 | `Assets/Scripts/Core/*` (`BlackHole.Core.*` asmdef) | `noEngineReferences`. (net8.0 시뮬레이션 CLI는 2026-10-14 `dev`에서 삭제되어 더는 빌드하지 않는다.) |
| 판 시작 | `UI/Flow/ScreenFlow.Battle.cs:406` `StartBattle()` → `BattleSystem.TryStart` → `GameSessionFactory.Create` | 시드는 `Environment.TickCount` |
| 결산 | `Core/Battle/Session/GameSession.cs` `End()` / `Settle()` | 마일스톤 달성 시 `max(0, TargetGold - Gold)`(그 판의 획득 골드는 버림), 미달성 시 `EarnedGold`. 성장 단계 +0 또는 +1 |
| 포기 | `ScreenFlow.Pause.cs` `TryAbandonAsync()` | 결산 없음 |
| 노드 구매 | `Core/Nodes/NodePurchase.cs` `TryPurchase` | 다음 랭크 1개. 검사 순서: 최대 랭크 → 노출(그래프 인접) → 골드. **결정적** |
| 진행도 | `Core/Battle/ProgressState.cs` | `Gold`, 노드별 랭크, `GrowthStage` |
| 저장 | `Save/ProgressStore.cs`, `Save/ProgressSaveFile.cs` | `progress.json`, `.tmp` → `File.Replace` 원자적 쓰기, `.prev` 백업 |
| 네트워크 | `Analytics/Transport/*` | 인증 없는 battle-summary 전송(파일 큐, 재시도)만 존재. 로그인·토큰·진행도 동기화 없음 |
| 헤드리스 실행 | (삭제됨) 이전: `Assets/Scripts/Simulation/*`, `Tools/Simulation` | 2026-10-14 `dev`에서 삭제. `BattleRunner`, `CampaignRunner`, 조준 봇이 있었다. 필요한 최소 부분(판 실행기, `NearestEnemyBot`, 구매 정책)은 W3-0에서 에디터 도구(`Editor/SyncFixtures`)로 새로 두었다(완료) |

**전투는 재현 불가능하다.** 라이브 게임은 가변 `Time.deltaTime`과 float 적분, 조준 입력 순서에 따라 결과가 달라지므로 시드만으로 재현되지 않는다. 따라서 서버 검증은 L1까지가 맞다.

**결산 값의 결정 구조** (검증 설계의 근거):

- 처치 1회의 골드·경험치는 (종류, 색 등급, 크기, 성질)로 정해진다. `EnemyDefinition.StatsAt`:
  - `gold = round(색 등급 Gold × 크기 배율)`, 황금이면 `round(gold × 황금 배율)`를 한 번 더
  - `exp = round(색 등급 Exp × 크기 배율)`
  - 크기 배율 `1 + (k − 1) × 1.0`은 코드 상수다(`SizeRule`). 반올림은 .5에서 올림.
- 황금 치명은 확률로 `round(gold × 치명 보상 배율)`을 보너스로 더한다(`DeathRewards`). **경험치에는 무작위 요소가 없다.**
- 레벨은 누적 경험치와 레벨 표만으로 정해지고, 목표 레벨에 닿으면 마일스톤이다(`Hq.RaiseLevels`).
- 제한 시간 = 기본 시간(+노드) + 오른 레벨 수 × 성장 시간 + 시간 추가 성공 수 × 처치 시간 보너스(`GameSession.ExtendTimeLimit`). 시간 추가는 수거형이 아닌 적의 사망마다 확률로 판정한다(`DeathBonuses`).
- 현재 `BattleRawData`는 처치 수를 **종류별로만** 센다.

**주의할 코드**: `ProgressSave.Capture`는 로드 시 보정된 값과 현재 값 중 큰 값을 쓴다. 서버 상태로 로컬을 덮어쓸 때는 이 경로를 거치면 안 된다.

### 2.2 서버

- Spring Boot 4.1.1 / Java 21 / MySQL 8.4 / Flyway(V1 인증, V2 진행도) / Testcontainers.
- 게스트 등록·로그인(guestId + secret), JWT(HS256, **15분**), refresh 토큰(30일) **회전**·재사용 탐지(재사용 시 계정의 모든 토큰 폐기)까지 완성.
- `GET/PUT /api/v1/me/progress`, `POST /api/v1/me/progress/reset`: 전체 상태 덮어쓰기. revision 낙관적 검사와 퇴행 검사만 하고 **골드·비용 검증 없음**.
- 노드 콘텐츠(`NodeTree`)를 CSV에서 시작 시 로드하지만 사용하는 곳이 없다.
- 판·결산·구매·로그 처리 코드 없음. `origin/feat/battleSummary`는 미병합이며 현재 마이그레이션과 충돌한다.
- CI는 checkstyle만 실행하고 테스트는 실행하지 않는다.

### 2.3 클라이언트와 서버의 콘텐츠 차이

계획 당시(2026-10-12)에는 서버 콘텐츠가 오래되고 전투 데이터가 없었다. W0-1(내보내기 도구), W0-3(서버 로드), W0-2(클라이언트 실행 중 버전)로 **차이가 모두 없어졌다.**

| 항목 | 계획 당시 | 현재 (2026-10-13) |
|---|---|---|
| 노드 / 스탯 / 랭크 행 | 클라이언트 276 / 71 / 348, 서버 247 / 60 / 319 | 같음. 서버는 클라이언트 내보내기 묶음을 그대로 쓴다(클라이언트 `dev` `cbc1f1f`에서 내보낸 결과와 7개 파일 바이트 일치 확인) |
| HQ 성장, 전투 규칙, 적 정보 | 클라이언트 `.asset`에만 있음 | 서버 `content/battle/battle.json`(계약 1.4) |
| 서버 콘텐츠 버전 | `nodeContentVersion`(노드만) | 묶음 `contentVersion`(계약 1.3) `4ab481c6a60d6e49`. 서버 시작 시 파일 바이트로 다시 계산해 확인 |
| 클라이언트 콘텐츠 버전(실행 중) | 릴리즈 빌드 `""` | 묶음 버전. 콘텐츠를 불러올 때 내보내기와 같은 루틴으로 계산한다(`LoadedContent.ServerContentVersion`, W0-2). 에디터 Play 모드와 빌드 모두 서버 목차의 값과 같음을 확인 |

## 3. 확정된 결정

| # | 질문 | 결정 |
|---|---|---|
| D1 | 새 게임(진행도 초기화) | 로그 타입 **`RESET`** 을 둔다. 서버는 별도 검증 없이 진행도를 초기화한다. |
| D2 | 로그가 거부되면 클라이언트는 | **서버 우선.** 로컬을 응답의 진행도로 덮어쓰고, 거부된 로그와 그 이후 로그는 폐기한다. 텍스트 UI로 알린다. |
| D3 | 로그와 서버의 콘텐츠 버전 불일치 | **거부한다**(`CONTENT_VERSION_MISMATCH`). 클라이언트는 그 실행을 오프라인으로 바꾸고 업데이트 필요를 알린다. 최근 몇 버전의 규칙을 보관해 하위 호환을 허용하는 것은 후속 과제(W5-3). |
| D4 | 첫 실행부터 오프라인이면 | 계정 없이 플레이한다. 첫 연결 때 게스트 등록 후 seq 1부터 쌓인 로그를 모두 전송한다. |
| D5 | 판 도중 앱이 종료되면 | 다음 `RUN_START`, `RESET`, `NODE_PURCHASE`가 오면 서버가 열린 판을 **포기**로 닫는다(보상 없음). 구매가 닫는 이유는 계약 C31. |
| D6 | battle-summary 통계와의 관계 | 진행도 로그와 **분리 유지**한다. `battleId`는 `runId`와 같은 값을 쓴다. |
| D7 | 결산 로그의 처치 수 단위 | **(종류, 색 등급, 크기, 황금 여부) 칸별**로 보낸다. 클라이언트 처치 집계를 이 단위로 바꾼다. |
| D8 | `RUN_START`가 서버 계산과 다르면 | **멈추지 않는다.** 판과 계정에 의심 표시를 남기고 계속 적용한다. 단, `RUN_START` 없이 온 결산이나 runId가 맞지 않는 결산은 거부한다(멈춤). |
| D9 | 오프라인 중 재연결 시점 | **1단계: 앱 시작 시에만** 연결을 시도한다. 2단계(W6): 안전한 지점(앱 재개, 타이틀·업그레이드 화면)에서 재연결을 추가한다. |
| D10 | 동기화 API | **`POST /api/v1/me/sync` 하나로 통일**한다. 로그가 0개여도 항상 진행도를 돌려준다. |

## 4. 콘텐츠 이관

### 4.1 절차

1. 클라이언트 저장소에서 콘텐츠를 편집한다.
2. 클라이언트 에디터 도구(W0-1)가 **서버 저장소 형식**(CSV + JSON)으로 내보내고, 내보낸 묶음의 해시를 **콘텐츠 버전**으로 정한다.
3. 같은 버전 값을 클라이언트 빌드에도 넣는다(현재 릴리즈 빌드는 `""`이므로 수정 필요).
4. 내보낸 파일을 서버 저장소 `src/main/resources/content/`에 커밋한다. 서버는 시작 시 같은 방식으로 해시를 계산하고, 버전 값을 기록과 응답에 쓴다.
5. 버전 업데이트마다 1~4를 반복한다. 클라이언트와 서버의 버전이 같은지는 배포 체크리스트로 확인한다.
6. **이식 검증 픽스처도 다시 만든다.** 기대값 표(W4-1a)와 골든 로그(W4-2a)는 콘텐츠 버전에 묶여 있다. 두 파일에 `contentVersion`을 넣고, 서버 테스트(W4-1b, W4-2b)가 서버 콘텐츠 버전과 다르면 "픽스처 재생성 필요"로 실패한다. 콘텐츠를 바꿀 때마다 2의 내보내기 뒤에 에디터 메뉴로 픽스처를 다시 내보낸다.

### 4.2 서버가 보유할 데이터 범위

검증(6장)에 필요한 것만 가져온다.

| 보유한다 | 보유하지 않는다 |
|---|---|
| 노드: `UpgradeStats`, `Nodes`, `NodeCost`, `NodeEffects`, 배치(시작 노드, 연결) | 적 HP, 이동 속도, 반지름, 배치·출현 띠 |
| HQ 성장: 레벨별 누적 경험치, 마일스톤(레벨, 목표 골드), 성장 단계별 시작·목표 레벨 | 블랙홀 공격 수치(피해, 간격, 범위, 치명) |
| 전투 규칙: 기본 제한 시간, 처치 시간 보너스 | 황금 외 사망 효과(번개, 폭발, 레이저, 달, 중력장) |
| 적 종류: id, 수거형 여부, 색 등급별 Gold·Exp, 변환 대상, 성질 목록 | 주기 출현(혜성) 확률·간격 |
| 황금 성질: 골드 배율, 치명 확률, 치명 보상 배율 (기본값) | |
| 수치 연결: 어떤 statId가 어떤 적의 질량·크기·시작 수·성장 공급·변환·시간 추가·성질 확률·황금 수치에 닿는가 (계약 C3) | |
| 시작 공급 목록 | |

파일 형식은 [계약 1장](server-sync-contracts.md#1-콘텐츠).

### 4.3 서버로 이식할 규칙 (데이터가 아니라 코드)

DTO만으로는 부족하다. 아래 계산식은 클라이언트 코드에 있으므로 Java로 옮긴다. 어떤 statId가 어디에 닿는지(`EnemyUpgradeStats`)는 옮기지 않고 콘텐츠 데이터로 받는다(계약 C3). 인터페이스와 수치 일치 규칙은 계약 1.6~1.7.

| 규칙 | 클라이언트 위치 |
|---|---|
| 노드 랭크 → 스탯 합산(최소·최대 보정 포함) | `NodePurchase.StatsFor`, `UpgradeStatValues` |
| 스탯 → 적 판 구성 반영(크기 상한, 질량과 색 비율, 황금 배율·치명, 시간 추가 확률, 성질 확률, 시작 수 보너스, 변환 수) | `EnemyComposition.From`의 해당 부분, `MassRule` |
| 칸별 골드·경험치 | `EnemyDefinition.StatsAt`, `SizeRule` |
| 시작 적 수(시작 수 + 보너스 → 변환 사슬 순서대로 이동) | `GameSessionFactory.StartSupplyOf` |
| 제한 시간, 성장 시간 | `TimeLimitDefinition.Upgraded`, `HqUpgradeStats.GrowthTime` |
| 시작 레벨, 누적 경험치, 다음 마일스톤, 마일스톤 보상 | `HqGrowthDefinition`, `HqMilestone.RewardFor` |

**이식 검증 방법**: 클라이언트 C# 코드(에디터 도구 `Editor/SyncFixtures`, W4-1a)로 여러 진행도 상태에 대한 **기대값 표**(시작 적 수, 제한 시간, 시작 레벨, 칸별 골드·경험치)를 생성한다. Java 테스트가 같은 입력에 대해 같은 값을 내는지 확인한다(W4-1).

## 5. 로그와 API

### 5.1 로그 공통 형식

```json
{
  "seq": 12,
  "type": "RUN_START | SETTLEMENT | NODE_PURCHASE | RESET",
  "occurredAtUtc": "2026-10-12T10:00:00Z",
  "buildVersion": "0.4.0",
  "contentVersion": "a1b2c3d4e5f60718",
  "payload": { }
}
```

- `seq`: 계정 단위로 1부터 1씩 증가한다. 클라이언트는 **다음 seq를 항상 서버가 돌려준 `appliedThroughSeq + 1`로 맞춘다**(오프라인 중에는 마지막 로컬 seq + 1).

### 5.2 타입별 payload

**NODE_PURCHASE**

```json
{ "nodeId": "timer-01", "rank": 2, "cost": 1500 }
```

`rank`는 구매 후 랭크.

**RESET**: payload 없음.

**RUN_START**

```json
{
  "runId": "uuid",
  "seed": 123456,
  "startGrowthStage": 1,
  "startGold": 52000,
  "startLevel": 10,
  "timeLimitSeconds": 15.0,
  "startSupply": [ { "enemyId": "asteroid", "count": 12 }, { "enemyId": "planet", "count": 2 } ]
}
```

**SETTLEMENT**

```json
{
  "runId": "uuid",
  "playedSeconds": 31.4,
  "kills": [
    { "enemyId": "asteroid", "tier": 0, "size": 1, "golden": false, "count": 140 },
    { "enemyId": "asteroid", "tier": 2, "size": 2, "golden": true,  "count": 3 }
  ],
  "goldenCritCount": 1,
  "goldenCritBonusGold": 900,
  "earnedGold": 48210,
  "exp": 91234,
  "reachedLevel": 14,
  "reachedMilestone": false,
  "timeBonusCount": 22,
  "settledGold": 48210,
  "nextGrowthStage": 1
}
```

- `kills`의 `count`는 1 이상이고, (enemyId, tier, size, golden) 조합은 중복되지 않는다.
- `BattleStats`(스킬별 피해 등)는 검증에 쓰지 않으므로 진행도 로그에 싣지 않는다. battle-summary 통계에만 남긴다.

### 5.3 `POST /api/v1/me/sync`

- 요청 `{ "logs": [ ... ] }`: seq 오름차순, 최대 N개(예: 500), 빈 배열 허용.
- 응답 `200` (필드 전체는 [API 명세](api-spec.md#동기화)):

```json
{
  "appliedThroughSeq": 11,
  "rejected": { "seq": 12, "type": "SETTLEMENT", "code": "SETTLEMENT_EXP_MISMATCH", "message": "..." },
  "contentVersion": "a1b2c3d4e5f60718",
  "progress": { "gold": 0, "growthStage": 0, "nodes": [ { "nodeId": "timer-01", "rank": 1 } ] }
}
```

- `rejected`는 거부가 없으면 `null`이다.
- 요청 형식 자체가 잘못된 경우(JSON 오류, 필수 필드 누락)만 기존 오류 형식의 400을 준다. 검증 실패는 항상 200 + `rejected`다.
- 기존 `PUT /me/progress`, `POST /me/progress/reset`은 **제거**한다. `GET /me/progress`는 유지하고 `lastAppliedSeq`를 추가한다.

## 6. 서버 적용과 검증

### 6.1 적용 규칙

1. 계정 진행도 행을 `PESSIMISTIC_WRITE`로 잠근다.
2. 로그를 seq 순서대로 처리한다.
   - `seq <= last_applied_seq`: 이미 반영됨. 건너뛴다(응답 유실 후 재전송 대응).
   - `seq > last_applied_seq + 1`: 누락. 거부하고 멈춘다.
   - 타입별 검증(6.2)을 통과하면 진행도에 적용하고 `last_applied_seq = seq`.
   - 검증에 실패하면 거부 사유를 기록하고 **멈춘다**. 이후 로그는 적용하지 않는다.
   - `RUN_START` 불일치는 실패로 보지 않는다(D8).
3. 적용된 결과를 커밋하고, 현재 진행도를 응답한다.
4. 받은 로그는 결과(적용·거부·건너뜀·미처리)와 함께 `sync_log`에 저장한다(감사, 버그 시 복구).

### 6.2 타입별 검증

**RESET**: 검증 없음. 열린 판을 포기로 닫고 진행도를 초기 상태로 되돌린다.

**NODE_PURCHASE**: `NodePurchase` 규칙을 그대로 적용한다(정확).

- 노드가 존재하고, 최대 랭크가 아니고, 노출되어 있고(시작 노드이거나 연결된 노드를 보유), 서버 잔액 ≥ 다음 랭크 비용.
- `rank`, `cost`가 서버 계산과 일치.
- 통과하면 골드를 차감하고 랭크를 올린다.

**RUN_START**: 진행도에 영향이 없다.

- 열린 판이 있으면 포기로 닫는다(D5). `runId` 중복이면 거부한다.
- 서버 진행도로 계산한 값과 비교한다: `startGrowthStage`, `startGold`, `startLevel`, `timeLimitSeconds`(오차 허용), `startSupply`.
- 불일치하면 판과 계정에 **의심 표시**만 남기고 판은 정상적으로 연다(D8).

**SETTLEMENT**: 진행도를 바꾸는 핵심 로그다. 처치 수 자체의 상한은 두지 않고 **값들이 서로 일관적인지** 확인한다.

| # | 검사 | 방식 |
|---|---|---|
| S1 | 열린 판이 있고 `runId`가 일치 | 정확 |
| S2 | 각 칸이 이번 판에서 가능한 조합(종류 존재, 이번 판 질량에서 나올 수 있는 색 등급, 크기 ≤ 이번 판 크기 상한, 황금은 이번 판에 황금 성질이 있고 생성 확률이 0보다 큰 종류만) | 정확 |
| S3 | `exp == 시작 레벨 누적 경험치 + Σ(칸 처치 수 × 칸 경험치)` | **정확** |
| S4 | `reachedLevel`, `reachedMilestone`이 경험치와 레벨 표로 계산한 값과 일치(목표 레벨을 넘지 않음) | 정확 |
| S5 | `goldenCritCount ≤ 황금 처치 수`, `goldenCritBonusGold`가 치명 횟수에 대해 가능한 보너스 범위 안 | 범위 |
| S6 | `earnedGold == Σ(칸 처치 수 × 칸 골드) + goldenCritBonusGold` | **정확** |
| S7 | `timeBonusCount ≤ 수거형이 아닌 처치 수` | 범위 |
| S8 | `playedSeconds ≤ 제한 시간 + (reachedLevel − 시작 레벨) × 성장 시간 + timeBonusCount × 처치 시간 보너스` | 범위(float 오차 허용) |
| S9 | `settledGold`, `nextGrowthStage`를 서버가 다시 계산해 일치 확인 | 정확 |
| S10 | (선택) 초당 처치 수, 치명 확률, 시간 추가 확률의 통계적 이상 | 느슨한 상한. 걸리면 의심 표시만 |

- 통과하면 판을 결산 완료로 닫고, **서버가 계산한** 결산 골드와 성장 단계를 적용한다.
- 이 설계에서는 골드를 부풀리려면 처치 수를 늘려야 하고, 그러면 경험치·레벨이 오르고, 제한 시간도 맞춰야 한다. 값이 서로 묶이므로 단순 수치 조작은 통과하지 못한다.

## 7. 클라이언트

### 7.1 로그인 (백그라운드)

```
앱 시작 ─▶ 로컬 진행도 + 대기 로그 로드 ─▶ 타이틀 표시("서버 연결 중")
  └─(백그라운드, 전체 과정에 타임아웃 하나. 예: 5초)
       refresh 토큰 있음? ─ 예 ─▶ 갱신 ─ 성공 ─────────────────┐
                │                   └ 실패 ─┐                   │
                └ 아니오 ──────────────────▶ 아이디/시크릿 있음? │
                                    ─ 예 ─▶ 로그인 ─ 성공 ──────┤
                                    └ 아니오 ─▶ 등록 ─▶ 로그인 ─┤
                                                               ▼
                                                     동기화(7.2) ─▶ 온라인
  타임아웃 또는 실패 ─▶ 오프라인 (D9: 이번 실행 중에는 재시도하지 않음)
```

- 액세스 토큰은 15분이라 저장된 것은 대부분 만료돼 있다. 그래서 refresh 토큰부터 시도한다.
- **새 refresh 토큰을 받으면 사용하기 전에 먼저 저장한다.** 서버가 토큰을 회전한 뒤 클라이언트가 저장하지 못하면, 다음 실행에서 옛 토큰이 재사용 탐지에 걸려 계정 토큰이 모두 폐기된다. 이 경우 아이디/시크릿 로그인으로 넘어가 복구된다.
- 실행 중 API가 401을 받으면 refresh로 한 번 갱신하고 재시도한다.
- 아이디, 시크릿, refresh 토큰은 `persistentDataPath`의 파일에 보관한다. 잃어버리면 새 게스트 계정이 된다(게스트 계정의 한계로 수용).

### 7.2 동기화

- **앱 시작 시**: 로그인 → 쌓인 로그 전송(없으면 빈 배열) → 응답의 진행도로 게임 시작.
  - 동기화가 끝나거나 타임아웃이 날 때까지 타이틀의 시작 버튼을 막는다. 그렇지 않으면 동기화 중에 로컬 진행도가 바뀐다.
- **온라인 중**: 로그가 생길 때마다 즉시 전송한다. 실패하면 로컬에 쌓고, 이번 실행 동안은 오프라인으로 전환한다(D9).
- **응답 처리**:
  - `appliedThroughSeq`까지의 대기 로그를 지운다.
  - `rejected`가 있으면 남은 대기 로그를 모두 폐기한다(D2).
  - 응답의 `progress`로 로컬 진행도를 덮어쓴다. `Capture`의 "큰 값 유지" 규칙을 거치지 않는 전용 경로를 쓴다.
  - 다음 seq를 `appliedThroughSeq + 1`로 맞춘다.
  - 거부가 없는데 서버 진행도와 로컬 진행도가 다르면 이식 버그다. 서버 값을 따르고 경고 로그를 남긴다.
- **원자성**: 진행도와 대기 로그를 같은 원자적 쓰기로 저장한다. 강제 종료로 둘이 어긋나지 않게 한다. 저장 포맷 버전을 2로 올린다.
- **거부 알림**: "일부 기록이 서버에서 거부되어 진행도가 조정되었습니다 (결산, 사유 코드)" 같은 텍스트를 타이틀 또는 업그레이드 화면에 한 번 표시한다.

### 7.3 로그 생성 지점

| 로그 | 지점 |
|---|---|
| `RUN_START` | `StartBattle`에서 `TryStart` 성공 직후 |
| `SETTLEMENT` | `EndBattleAsync`에서 `Settle` 직후 |
| `NODE_PURCHASE` | `NodeTreeView`에서 `TryPurchase`가 `Purchased`일 때 |
| `RESET` | 모드 선택의 새 게임(`ProgressStore.StartNew`) |

- 결산 로그를 위해 코어의 처치 집계를 (종류, 색 등급, 크기, 황금 여부) 칸별로 확장하고, 황금 치명 횟수·보너스와 시간 추가 성공 수를 따로 센다(D7).

## 8. 데이터 모델 (서버, V3 마이그레이션)

- `player_progress`
  - 추가: `last_applied_seq BIGINT NOT NULL DEFAULT 0`, `open_run_id CHAR(36) NULL`, `suspicious_count INT NOT NULL DEFAULT 0`
  - 제거: `revision`(`last_applied_seq`로 대체, 옛 API 제거와 함께, V4 — 완료)
- `run` (신규)
  - `id`, `account_id`, `run_id CHAR(36) UNIQUE`, `seed`, `start_growth_stage`, `start_gold`
  - `status`(OPEN / SETTLED / ABANDONED), `suspicious BOOLEAN`, `suspicious_reason NULL`
  - `settled_gold NULL`, `next_growth_stage NULL`, `started_seq`, `ended_seq NULL`, 생성·수정 시각
- `sync_log` (신규)
  - `id`, `account_id`, `seq`, `type`, `content_version`, `build_version`, `payload JSON`
  - `result`(APPLIED / REJECTED / SKIPPED / NOT_PROCESSED), `reject_code NULL`, `received_at`
  - 같은 seq가 여러 번 들어올 수 있으므로(재전송, 거부 후 재사용) seq에 유니크를 걸지 않는다. 적용 여부의 기준은 잠금 아래의 `last_applied_seq`다.

## 9. 작업 목록

### W0. 콘텐츠 이관 (선행)

| ID | 영역 | 규모 | 작업 |
|---|---|---|---|
| W0-1 | C | M | 서버용 콘텐츠 내보내기 에디터 도구(4.2 범위), 내보낸 묶음의 해시로 콘텐츠 버전 계산 |
| W0-2 | C | S | 릴리즈 빌드에도 콘텐츠 버전을 넣는다 |
| W0-3 | S | M | 서버가 묶음(목차, 노드, `battle.json`)을 로드해 계약 1.5 객체로 만들고 버전 검증. `nodeContentVersion`을 `contentVersion`으로 대체, 노드 수 검증 테스트 갱신, `import_node_content.py` 대체 |

### W1. 서버

| ID | 영역 | 규모 | 선행 | 작업 |
|---|---|---|---|---|
| W1-1 | S | S | - | V3 마이그레이션, 엔티티, 리포지토리 |
| W1-2 | S | M | W1-1 | 로그 DTO, `POST /me/sync`, 적용 엔진(순차 적용·멈춤·건너뜀·누락, 항상 진행도 응답) |
| W1-3 | S | M | W0-3, W1-2 | `NODE_PURCHASE`(`NodePurchase` 이식), `RESET` 처리기. **완료**(B) |
| W1-4 | S | L | W0-3 | 전투 규칙 이식(4.3, 계약 1.6의 `BattleRules` 구현): 스탯 합산, 판 구성 일부, 질량 규칙, 칸별 골드·경험치, 시작 적 수, 제한 시간, Level 계산. **완료**(B, C# 기대값 표 일치는 W4-1b) |
| W1-5 | S | S | W1-2, W1-4 | `RUN_START` 처리기, 의심 표시 |
| W1-6 | S | M | W1-2, W1-4 | `SETTLEMENT` 처리기, `SettlementValidator`(S1~S9). **완료**(A, S10 제외) |
| W1-7 | S | S | W1-3 | 옛 API 제거, `GET /me/progress`에 `lastAppliedSeq` 추가. **완료**(B, `revision` 열 제거 V4는 A) |
| W1-8 | S | M | W1-2~6 | E2E(`FakeClient`): 순차 적용, 재전송, 누락, N번째 거부 시 N-1까지 반영, `RUN_START` 불일치 시 계속 진행, 동시 요청. CI에 테스트 단계 추가 |

### W2. 클라이언트 로그인

| ID | 영역 | 규모 | 선행 | 작업 |
|---|---|---|---|---|
| W2-1 | C | M | - | `BlackHole.Net` 어셈블리: HTTP 계층(`AnalyticsClient` 패턴 재사용), 테스트용 인터페이스, 오류 형식 파싱 |
| W2-2 | C | M | W2-1 | 계정 정보 저장, 7.1 순서(갱신 → 로그인 → 등록), 401 시 갱신 후 재시도 |
| W2-3 | C | S | W2-2 | 연결 상태(연결 중 / 온라인 / 오프라인), 전체 타임아웃, 타이틀 상태 표시와 시작 버튼 대기 |

### W3. 클라이언트 로그와 동기화

| ID | 영역 | 규모 | 선행 | 작업 |
|---|---|---|---|---|
| W3-0 | C | S | - | 판 실행기 복원(에디터 도구): 판 실행기, `NearestEnemyBot`, 구매 정책. W3-1의 검증과 W4-1a·W4-2a가 쓴다. **완료** |
| W3-1 | C | M | W3-0 | 코어 처치 집계를 칸별로 확장, 치명 횟수·보너스, 시간 추가 성공 수 집계. **완료**(PR #88) |
| W3-2 | C | M | W0-2, W3-1 | 코어에 로그 모델과 생성기, 4개 지점에서 생성. **완료**(A가 진행, 칸별 값은 W3-1이 채운다, 완료) |
| W3-3 | C | M | W3-2 | 저장 포맷 2: 진행도와 대기 로그를 같은 원자적 쓰기로 저장, 포맷 1 마이그레이션. **완료**(A가 진행) |
| W3-4 | C | M | W2-3, W3-3, W1-2 | 동기화 전송기: 앱 시작 시 일괄 전송, 온라인 중 즉시 전송, 응답 처리(7.2) |
| W3-5 | C | S | W3-4 | 서버 진행도로 덮어쓰기 전용 경로, 거부 알림 텍스트 UI |

### W4. 이식 검증과 계약 테스트

| ID | 영역 | 규모 | 선행 | 작업 |
|---|---|---|---|---|
| W4-1 | C+S | M | W1-4 | 에디터 도구(`Editor/SyncFixtures`)로 기대값 표 생성, Java 이식 결과가 같은지 테스트 |
| W4-2 | C+S | M | W1-6, W3-0, W3-2 | 캠페인 로그를 골든 픽스처로 생성(에디터 도구, 현재 봇이 닿는 성장도 3까지). 골든 로그는 전부 통과, 조작 로그(골드 부풀리기, 경험치 불일치, 시작 없는 결산, 비용 위조 등)는 해당 지점에서 멈추는지 테스트 |
| W4-3 | C+S | S | W1-2, W3-2 | JSON 샘플 기반 직렬화 계약 테스트(양쪽 저장소에 같은 샘플) |

### W5. 정리

| ID | 영역 | 규모 | 작업 |
|---|---|---|---|
| W5-1 | C+S | S | battle-summary의 `battleId`를 `runId`와 통일(D6). 서버 `feat/battleSummary` 재작업 또는 폐기 결정 |
| W5-2 | S | S | API 명세 갱신 |
| W5-3 | C+S | L | (후속, 이번 범위 밖) 최근 몇 버전의 콘텐츠·규칙을 보관해 옛 버전 로그를 하위 호환으로 받기(D3) |

### W6. 재연결 확장 (2단계, D9)

| ID | 영역 | 규모 | 선행 | 작업 |
|---|---|---|---|---|
| W6-1 | C | M | W3-5 | 안전한 지점(앱 재개, 타이틀·업그레이드 화면, 전투 중 제외)에서 재연결·동기화. 동기화 중 진행도 변경을 막는 처리 |

### 선행 관계 요약

```
W0-1 ─▶ W0-2 ─▶ W3-2
  └──▶ W0-3 ─▶ W1-3
            └▶ W1-4 ─▶ W1-5, W1-6 ─▶ W4-1, W4-2
W1-1 ─▶ W1-2 ─▶ W1-3, W1-5, W1-6, W3-4
W2-1 ─▶ W2-2 ─▶ W2-3 ─▶ W3-4
W3-0 ─▶ W3-1 ─▶ W3-2 ─▶ W3-3 ─▶ W3-4 ─▶ W3-5 ─▶ W6-1
W3-0 ─▶ W4-2
```

- 계약 문서(A0)를 먼저 확정하면 서버(W1)와 클라이언트(W2, W3)를 병렬로 진행할 수 있다.
- 가장 큰 위험은 W1-4(전투 규칙 이식)다. W4-1의 기대값 표를 W1-4와 함께 만든다.

## 10. 알려진 한계

- 값들이 서로 일관되도록 조작한 로그(예: 처치 수, 경험치, 시간을 함께 맞춘 로그)는 막지 못한다. 목표는 단순 수치 조작보다 어렵게 만드는 것이다.
- 클라이언트가 유리한 시드를 고르는 것은 막지 못한다.
- 1단계에서는 앱 시작 시에만 연결하므로, 시작 때 오프라인이었던 실행은 다음 실행까지 서버에 반영되지 않는다(D9).
- 게스트 계정 정보를 잃으면 진행도를 복구할 수 없다.
- 이식 검증 픽스처(기대값 표, 골든 로그)는 콘텐츠 버전마다 다시 만들어야 한다. 밸런스 조정이 잦으면 재생성이 자주 필요하다(4.1의 6).
- 골든 로그는 현재 봇이 닿는 성장도 3(Lv30)까지만 덮는다. 마지막 이정표(Lv40) 이후의 서버 분기는 단위 테스트(W1-6)로 검증한다(봇을 강화하면 넓힐 수 있다, 작업지시 W4-2a).
- 콘텐츠 버전이 서버와 다른 로그는 거부된다(D3). 오프라인 중 클라이언트가 업데이트되면 그 전에 쌓인 로그는 반영되지 않는다. 배포 순서(서버 먼저, 클라이언트 나중)를 지켜야 하고, 개발 중에도 클라이언트에서 콘텐츠를 바꾸면 서버로 내보내야 온라인 기록이 받아들여진다.
