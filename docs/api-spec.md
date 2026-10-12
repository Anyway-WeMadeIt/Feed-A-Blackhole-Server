# API 명세 (초안)

클라이언트(Unity)와 서버(Spring Boot)의 통신 계약이다. 변경은 이 문서를 먼저 고친 뒤 구현에 반영한다.

- 상태: **초안 v0.7** (2026-10-12). 설계와 근거는 [server-sync-plan.md](server-sync-plan.md)(이하 "계획서"), 담당과 순서는 [server-sync-assignment.md](server-sync-assignment.md), 콘텐츠 형식과 내부 인터페이스는 [server-sync-contracts.md](server-sync-contracts.md)에 있다.
- v0.6과의 차이: 동기화를 **계정 단위 순번(`seq`)을 가진 로그** 방식으로 바꿨다. `batchId`·`baseRevision`·`BASE_MISMATCH`·`claimed`를 없앴고, 전투는 **판 시작(`RUN_START`)과 결산(`SETTLEMENT`) 두 로그**로 나눴다. `revision`은 `lastAppliedSeq`로 대체한다.
- 구현 현황: 인증 전체(게스트 등록·로그인, 토큰 갱신·로그아웃), `GET /me`, 진행 데이터(불러오기·저장·초기화), 공통 오류 응답이 구현되어 있다. 이 중 **저장(`PUT /me/progress`)과 초기화(`POST /me/progress/reset`)는 검증을 우회하는 경로이므로 제거한다(W1-7).** 동기화 API는 구현 전이다.
- 필드 타입·범위·필수 여부, 오류를 내는 위치, 한도, 거부 코드의 확정본은 [계약 3장](server-sync-contracts.md#3-로그동기화-json)이다. 이 문서에서 **(A0 확정)** 으로 표시한 값은 아직 잠정값이다.

## 설계 원칙

- **클라이언트가 게임을 실행하고 로컬 진행도를 가진다.** 노드 구매, 전투, 새 게임은 로컬에서 바로 적용하고, 같은 내용을 **로그**로 기록한다. 온라인이면 곧바로, 오프라인이면 쌓아 두었다가 다음 앱 시작 때 연결되면 서버에 제출한다.
- **서버는 정본을 가지고 로그를 검증한다.** 서버는 DB의 진행도에 로그를 seq 순서대로 적용한다. 잘못된 로그를 만나면 **그 직전까지만 반영**하고 멈춘다.
- **서버가 계산한 진행도가 항상 이긴다.** 동기화 응답에는 결과와 상관없이 항상 서버 진행도가 담기고, 클라이언트는 그것을 채택한다. 클라이언트가 보낸 결산 골드 등은 **검증 대상일 뿐 정산에는 서버 계산값을 쓴다.**
- **전투 검증은 L1까지다.** 결산 값들이 서로 일관적인지(처치 장부와 Gold·EXP, Level, 시간, 결산 규칙)만 본다. 값들을 서로 맞춰 조작한 로그는 막지 못하며, 이 한계는 수용한다.
- **콘텐츠 원본은 클라이언트 저장소다.** 서버는 버전마다 클라이언트에서 내보낸 콘텐츠 묶음을 받아 둔다. 모든 로그는 자신이 쓴 콘텐츠 버전(`contentVersion`)을 싣는다.
- 노드 이름·아이콘·표시 문자열 같은 **표현 데이터는 클라이언트 소유**다. 서버는 `nodeId`·`statId`·`enemyId` 같은 키만 다룬다.

## 공통 규칙

| 항목 | 규칙 |
|---|---|
| 기본 경로 | `/api/v1` |
| 형식 | JSON, UTF-8 |
| 인증 | `Authorization: Bearer <accessToken>` (`/auth/**` 제외) |
| Gold | JSON 정수(int64). 문자열로 감싸지 않는다. 금액에 실수형을 쓰지 않는다. |
| 시각 | UTC, ISO-8601 |

### 오류 응답

```json
{ "error": { "code": "INVALID_REQUEST", "message": "..." } }
```

클라이언트는 `code`로 분기하고 `message`는 디버깅용으로만 쓴다. 이 형식은 **요청 단위 오류**에만 쓴다. 동기화에서 로그가 검증에 실패한 것은 오류가 아니라 정상 응답(`200`)의 `rejected`로 알린다([동기화](#동기화)).

| code | 상태 | 의미 |
|---|---|---|
| `INVALID_REQUEST` | 400 | 필수 필드 누락·길이 초과, 잘못된 JSON 등. 필드 검증 실패는 `message`에 필드명이 담긴다. |
| `INVALID_CREDENTIALS` | 401 | 로그인 정보가 맞지 않음. |
| `UNAUTHORIZED` | 401 | 인증이 필요한 API에 액세스 토큰이 없거나, 만료·위조 등으로 유효하지 않음. 클라이언트는 리프레시 토큰으로 갱신을 시도한다. |
| `INVALID_REFRESH_TOKEN` | 401 | 리프레시 토큰이 없는 값이거나 만료·폐기됨. 클라이언트는 `guestId`·`guestSecret`으로 다시 로그인한다. |
| `NOT_FOUND` | 404 | 없는 경로. |
| `METHOD_NOT_ALLOWED` | 405 | 경로는 있으나 지원하지 않는 HTTP 메서드. |
| `PAYLOAD_TOO_LARGE` | 413 | 요청의 로그 수나 본문 크기가 한도를 넘음([한도](#한도)). (신규) |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 지원하지 않는 `Content-Type`. |
| `INTERNAL_ERROR` | 500 | 서버 내부 오류. 상세는 서버 로그에만 남기고 응답에는 담지 않는다. |

현재 코드에 있는 `STALE_PROGRESS`, `PROGRESS_REGRESSION`(409)은 저장 API와 함께 제거한다(W1-7).

## 인증

토큰 없이 호출한다(`/auth/**` 전체. 이외의 API는 모두 액세스 토큰이 필요하다). 계정 모델은 `account` / `auth_identity` / `refresh_token` 테이블(`V1__create_auth_tables.sql`)을 따른다. 게스트의 `guestId`는 `auth_identity.identifier`, `guestSecret`은 `secret_hash`(SHA-256)로 저장된다.

| | 경로 | 설명 |
|---|---|---|
| POST | `/auth/guest/register` | 새 게스트 계정을 만들고 `guestId`, `guestSecret`을 발급한다. 계정 생성 시 진행 데이터를 처음 상태(Gold 0, 성장도 0, 산 노드 없음, `lastAppliedSeq` 0)로 만든다. |
| POST | `/auth/guest/login` | `{guestId, guestSecret}`으로 로그인하고 `accessToken`, `refreshToken`을 받는다. |
| POST | `/auth/refresh` | 리프레시 토큰으로 새 토큰 쌍을 받는다. 토큰은 사용할 때마다 교체(회전)된다. |
| POST | `/auth/logout` | 리프레시 토큰을 폐기한다. |
| GET | `/me` | (인증 필요) 액세스 토큰이 가리키는 계정. |

- `guestSecret`은 클라이언트가 보관한다. 서버는 해시만 보관하므로 분실하면 복구할 수 없다.
- 기기 분실이나 재설치로 자격과 **아직 동기화하지 않은 로그**를 잃는 것은 수용한다.
- 단일 기기 로그인은 구현하지 않는다. 같은 계정이 두 기기에서 갈라져 진행하면, 먼저 도착한 로그가 적용되고 늦게 온 쪽은 seq 불일치로 거부되어 서버 진행도로 맞춰진다.

### POST `/auth/guest/register`

요청 본문은 없다.

```json
// 201 Created
{ "guestId": "ccd9b782-14b3-4a62-a004-22951ad0eeb3", "guestSecret": "O4uOMSZI3-pBa3mb8aSS2xRuxzTT02AX3IglV4dxPSk" }
```

- `guestId`: 서버가 만든 랜덤 UUID. 계정을 구분하는 값이며 비밀이 아니다(로그·문의에 써도 된다).
- `guestSecret`: 서버가 만든 256비트 랜덤 값(base64url, 43자). **이 응답에서만 원본을 볼 수 있다.**
- 호출할 때마다 새 계정이 만들어진다. 클라이언트는 저장된 자격증명이 없을 때에만 호출한다.

### POST `/auth/guest/login`

```json
// 요청 (두 필드 모두 필수. guestId ≤ 255자, guestSecret ≤ 128자)
{ "guestId": "ccd9b782-14b3-4a62-a004-22951ad0eeb3", "guestSecret": "O4uOMSZI3-..." }

// 200 OK
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9...", "expiresIn": 900, "refreshToken": "kObw44EmutxdPuBKFcIkN7eKsLc1amB79wSA128ucWU" }
```

- 로그인에 성공하면 계정의 `last_login_at`이 갱신된다.
- `guestId`가 없는 경우와 `guestSecret`이 틀린 경우는 **같은 `401 INVALID_CREDENTIALS` 응답**이다.
- 입력 형식 오류(빈 값, 길이 초과, 잘못된 JSON)는 `400 INVALID_REQUEST`.

### 토큰

로그인과 갱신은 같은 형식의 응답(`TokenResponse`)을 돌려준다.

| 필드 | 설명 |
|---|---|
| `accessToken` | API 호출에 쓰는 JWT(HS256). 수명 15분. 담는 값은 발급자(`iss`), 계정 ID(`sub`), 발급·만료 시각(`iat`, `exp`)뿐이다. |
| `expiresIn` | 액세스 토큰이 몇 초 뒤 만료되는지(현재 900). |
| `refreshToken` | 새 액세스 토큰을 받는 데 쓰는 불투명한 랜덤 값(43자). 수명 30일. **한 번 쓰면 폐기되므로 응답의 새 값으로 교체해 저장해야 한다.** |

### 클라이언트 로그인 흐름

앱 시작 시 **백그라운드에서** 사용자 조작 없이 수행한다. 전체 과정에 타임아웃 하나를 둔다(예: 5초, **A0 확정**). 시간 안에 끝나지 않으면 그 실행은 오프라인으로 진행한다.

```
refreshToken 있음 → POST /auth/refresh ── 성공 ──────────────────────────────┐
                        └ 401 INVALID_REFRESH_TOKEN ─┐                        │
refreshToken 없음 ───────────────────────────────────┤                        │
                                                     ▼                        │
                  guestId·guestSecret 있음 → POST /auth/guest/login ─ 성공 ───┤
                  없음 → POST /auth/guest/register → POST /auth/guest/login ─┤
                                                                              ▼
                                                              POST /me/sync (동기화)
API 호출 중 401 UNAUTHORIZED → POST /auth/refresh 후 같은 요청을 한 번 재시도
```

- 기기에 저장하는 값은 `guestId`, `guestSecret`, `refreshToken`이다. 액세스 토큰은 15분이라 메모리에만 둔다.
- 갱신·로그인 응답의 새 `refreshToken`은 **사용하기 전에 먼저 저장한다.** 저장하지 못한 채 앱이 종료되면 다음 실행에서 옛 토큰이 재사용 탐지에 걸려 계정의 리프레시 토큰이 모두 폐기된다. 이때는 `401 INVALID_REFRESH_TOKEN`을 받고 `guestId`·`guestSecret` 로그인으로 넘어가 복구된다.
- 갱신 요청은 한 번에 하나만 보낸다. 같은 토큰으로 겹쳐 보내면 재사용 탐지에 걸린다.
- `401 UNAUTHORIZED`로 거부된 요청은 서버가 처리하지 않았으므로 재시도해도 이중으로 적용되지 않는다.
- 기준 구현은 `src/test/java/org/example/feedablackhole/support/FakeClient.java`이며, 동기화 구현과 함께 새 흐름을 따르도록 고친다(W1-8).

### POST `/auth/refresh`

```json
// 요청
{ "refreshToken": "kObw44EmutxdPuBKFcIkN7eKsLc1amB79wSA128ucWU" }

// 200 OK — 로그인 응답과 같은 형식. 쓴 리프레시 토큰은 폐기되고 새 값이 들어 있다.
{ "accessToken": "...", "expiresIn": 900, "refreshToken": "새 값" }
```

- 이미 쓴(폐기된) 리프레시 토큰이 다시 들어오면 **`401 INVALID_REFRESH_TOKEN`으로 거부하고, 그 계정의 모든 리프레시 토큰을 폐기한다.**
- 없는 토큰, 만료된 토큰, 폐기된 토큰은 모두 같은 `401 INVALID_REFRESH_TOKEN`이다.
- 같은 토큰으로 동시에 들어온 요청은 서버가 한 번에 하나씩 처리한다.

### POST `/auth/logout`

```json
// 요청
{ "refreshToken": "kObw44EmutxdPuBKFcIkN7eKsLc1amB79wSA128ucWU" }

// 204 No Content
```

- 해당 리프레시 토큰을 폐기한다. 이미 폐기됐거나 없는 토큰이어도 `204`이다.
- 이미 발급된 액세스 토큰은 만료(최대 15분)까지 유효하다.

### GET `/me`

```json
// 200 OK
{ "accountId": 3 }
```

- 액세스 토큰이 없거나 유효하지 않으면 `401 UNAUTHORIZED`이며 `WWW-Authenticate: Bearer` 헤더가 붙는다.

## 진행 데이터

계정의 진행 상태(Gold, 블랙홀 성장도, 산 노드와 Rank)의 **서버 정본**이다. 계정은 토큰에서 정해진다. 진행 상태를 바꾸는 방법은 동기화뿐이다.

```json
{
  "lastAppliedSeq": 14,
  "gold": 1780000,
  "growthStage": 3,
  "nodes": [ { "nodeId": "timer-01", "rank": 1 }, { "nodeId": "timer-02", "rank": 2 } ],
  "updatedAt": "2026-10-12T06:38:15.680980Z"
}
```

| 필드 | 설명 |
|---|---|
| `lastAppliedSeq` | 서버가 지금까지 적용한 마지막 로그의 seq. 처음 상태는 0. 클라이언트의 다음 로그 seq는 이 값 + 1이다. |
| `gold` | Gold. int64(0 이상). |
| `growthStage` | 블랙홀의 성장도(도달한 이정표 수, 0 이상). |
| `nodes` | 산 노드와 산 Rank. 처음 산 순서이고, 사지 않은 노드는 없다. |
| `updatedAt` | 마지막으로 바뀐 시각(UTC). |

### GET `/me/progress`

서버의 진행 상태를 불러온다. 위 형식으로 응답한다. 클라이언트는 보통 로그 없는 `POST /me/sync`로 같은 정보를 받으므로, 이 API는 진단·확인용이다.

### 제거하는 API

| | 경로 | 대체 |
|---|---|---|
| PUT | `/me/progress` | `POST /me/sync` |
| POST | `/me/progress/reset` | `POST /me/sync`의 `RESET` 로그 |

## 동기화

### POST `/me/sync`

클라이언트가 쌓은 **로그**를 서버에 제출한다. 서버는 DB의 진행도에 로그를 **seq 순서대로 적용하며 검증**하고, 결과와 서버 진행도를 돌려준다. 로그가 없어도 호출할 수 있으며, 이때는 서버 진행도만 받는다.

```json
// 요청
{
  "logs": [
    { "seq": 12, "type": "NODE_PURCHASE", "occurredAtUtc": "2026-10-12T10:00:00Z",
      "buildVersion": "0.4.0", "contentVersion": "a1b2c3d4e5f60718",
      "payload": { "nodeId": "timer-01", "rank": 2, "cost": 1500 } },
    { "seq": 13, "type": "RUN_START", "occurredAtUtc": "2026-10-12T10:00:05Z",
      "buildVersion": "0.4.0", "contentVersion": "a1b2c3d4e5f60718",
      "payload": { "runId": "4b0e…", "seed": 123456, "...": "..." } }
  ]
}

// 200 OK
{
  "appliedThroughSeq": 12,
  "rejected": { "seq": 13, "type": "RUN_START", "code": "DUPLICATE_RUN_ID", "message": "..." },
  "contentVersion": "a1b2c3d4e5f60718",
  "progress": { "lastAppliedSeq": 12, "gold": 0, "growthStage": 0, "nodes": [], "updatedAt": "2026-10-12T10:00:06Z" }
}
```

### 요청: 로그 공통 필드

| 필드 | 타입 | 설명 |
|---|---|---|
| `logs` | 배열 | seq 오름차순. 비어 있어도 된다. 최대 개수는 [한도](#한도). |
| `seq` | int64 | 계정 단위 순번. 1부터 1씩 증가한다. |
| `type` | 문자열 | `RUN_START`, `SETTLEMENT`, `NODE_PURCHASE`, `RESET` |
| `occurredAtUtc` | 문자열 | 클라이언트 시각. 기록용이며 검증에 쓰지 않는다. |
| `buildVersion` | 문자열 | 클라이언트 빌드 버전. 기록용. |
| `contentVersion` | 문자열 | 이 로그를 만들 때 쓴 콘텐츠 묶음의 버전 해시. 서버 버전과 다르면 `CONTENT_VERSION_MISMATCH`로 거부한다. |
| `payload` | 객체 | 타입별 내용. `RESET`은 빈 객체 또는 생략. |

### 로그 타입

#### `NODE_PURCHASE`: 노드 구매 (다음 Rank 하나)

```json
{ "nodeId": "timer-01", "rank": 2, "cost": 1500 }
```

| 필드 | 설명 |
|---|---|
| `nodeId` | 산 노드 |
| `rank` | **구매 후의 Rank.** 서버의 현재 Rank + 1과 같아야 한다. |
| `cost` | 지불한 Gold. 서버의 해당 Rank 비용과 같아야 한다. |

서버는 노드가 서버의 노드 콘텐츠에 있는지, 최대 Rank가 아닌지, 드러나 있는지(시작 노드이거나 연결된 노드를 가짐), 서버 잔액이 비용 이상인지 확인하고 Gold를 차감한다. 검사 순서는 클라이언트 `NodePurchase`와 같다.

#### `RESET`: 새 게임

payload가 없다. 검증 없이 Gold, 성장도, 산 노드를 처음 상태로 되돌린다. 열린 판이 있으면 포기로 닫는다. `lastAppliedSeq`는 계속 오른다(초기화되지 않는다).

#### `RUN_START`: 판 시작

```json
{
  "runId": "4b0e9c1a-…",
  "seed": 123456,
  "startGrowthStage": 1,
  "startGold": 52000,
  "startLevel": 10,
  "timeLimitSeconds": 15.0,
  "startSupply": [ { "enemyId": "asteroid", "count": 12 }, { "enemyId": "planet", "count": 2 } ]
}
```

| 필드 | 설명 |
|---|---|
| `runId` | 클라이언트가 만든 UUID(소문자). battle-summary 통계의 `battleId`와 같은 값이다. |
| `seed` | 난수 시드. 기록용. |
| `startGrowthStage` | 시작 때의 성장도 |
| `startGold` | 시작 때의 Gold |
| `startLevel` | 블랙홀 시작 Level |
| `timeLimitSeconds` | 노드가 반영된 시작 제한 시간(초) |
| `startSupply` | 시작 공급(종류와 수, 변환 반영). 처음 나온 종류 순서. `enemyId`는 소문자 종류 id(`asteroid`, `planet`, `star`, `comet`, `galaxy`, 계약 1.4). |

- 진행도를 바꾸지 않는다. 판(run)을 열고, 이미 열린 판이 있으면 그 판을 **포기**로 닫는다(보상 없음).
- 서버는 자기 진행도로 같은 값을 계산해 비교한다. **값이 달라도 거부하지 않고** 판과 계정에 의심 표시만 남긴 뒤 판을 연다.
- `runId`가 이미 쓰인 값이면 거부한다(`DUPLICATE_RUN_ID`).

#### `SETTLEMENT`: 판 결산

```json
{
  "runId": "4b0e9c1a-…",
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

| 필드 | 설명 |
|---|---|
| `runId` | 결산하는 판. 열린 판의 `runId`와 같아야 한다. |
| `playedSeconds` | 판의 경과 시간(초) |
| `kills` | 처치 장부. 항목은 `{ enemyId, tier, size, golden, count }`: 적 종류, 색 등급(0부터), 크기(1부터), 황금 여부, 처치 수(1 이상). 같은 (enemyId, tier, size, golden) 조합은 한 번만 나온다. |
| `goldenCritCount` | 황금 보너스 치명 횟수 |
| `goldenCritBonusGold` | 황금 보너스 치명으로 더해진 Gold 합 |
| `earnedGold` | 처치 보상의 합(장부 Gold + 치명 보너스) |
| `exp` | 판이 끝났을 때 블랙홀의 누적 EXP(시작 Level의 누적 EXP부터 센다) |
| `reachedLevel` | 도달한 블랙홀 Level |
| `reachedMilestone` | 이정표에 닿았는지 |
| `timeBonusCount` | 처치 시 시간 추가에 성공한 횟수 |
| `settledGold` | 결산 금액. 이정표에 닿았으면 `max(0, 목표 Gold − 현재 Gold)`, 아니면 `earnedGold` |
| `nextGrowthStage` | 결산 후 성장도(이정표에 닿았으면 +1) |

`kills`가 이 형식인 이유: 처치 하나의 Gold·EXP는 (종류, 색 등급, 크기, 황금)으로 정해진다. 종류별 수량만으로는 Gold와 EXP의 장부를 대조할 수 없다.

서버의 검사는 다음과 같다(계획서 6.2의 S1~S9).

| # | 검사 |
|---|---|
| S1 | 열린 판이 있고 `runId`가 일치한다 |
| S2 | 각 장부 항목이 이번 판에서 가능한 조합이다(종류, 이번 판 질량에서 나올 수 있는 색 등급, 크기 상한, 황금 성질 보유와 생성 확률) |
| S3 | `exp` = 시작 Level의 누적 EXP + Σ(처치 수 × 항목 EXP) |
| S4 | `reachedLevel`, `reachedMilestone`이 EXP와 Level 표에서 도출된다 |
| S5 | `goldenCritCount` ≤ 황금 처치 수, `goldenCritBonusGold`가 가능한 범위 안이다 |
| S6 | `earnedGold` = Σ(처치 수 × 항목 Gold) + `goldenCritBonusGold` |
| S7 | `timeBonusCount` ≤ 수거형이 아닌 처치 수 |
| S8 | `playedSeconds` ≤ 시작 제한 시간 + (`reachedLevel` − 시작 Level) × 성장 시간 + `timeBonusCount` × 처치 시간 보너스 (허용 오차 **A0 확정**) |
| S9 | `settledGold`, `nextGrowthStage`가 서버 계산과 같다 |

통과하면 판을 결산 완료로 닫고, **서버가 계산한** 결산 금액과 성장도를 진행도에 적용한다.

### 응답 필드

| 필드 | 설명 |
|---|---|
| `appliedThroughSeq` | 이 요청 처리 후 서버의 `lastAppliedSeq`. 이 값 이하의 로그는 모두 반영되었다. |
| `rejected` | 거부된 로그가 있으면 `{ seq, type, code, message }`, 없으면 `null`. `message`는 디버깅용이다. |
| `contentVersion` | 서버의 콘텐츠 묶음 버전 |
| `progress` | **항상 담긴다.** 응답 시점의 서버 진행도([진행 데이터](#진행-데이터)와 같은 형식) |

### 적용 규칙

1. 같은 계정의 동기화는 서버가 한 번에 하나씩 처리한다(진행도 행 잠금). 동시에 온 요청은 앞 요청이 끝난 뒤 처리된다.
2. 로그를 **seq 순서대로** 처리한다. 각 로그는 직전 로그까지 적용된 상태에서 검증한다.
   - `seq ≤ lastAppliedSeq`: 이미 반영된 로그다. **건너뛴다**(응답을 받지 못해 다시 보낸 경우).
   - `seq > lastAppliedSeq + 1`: 앞 로그가 빠졌다. `SEQ_GAP`으로 거부한다.
   - 검증에 통과하면 적용하고 `lastAppliedSeq = seq`.
   - 검증에 실패하면 거부하고 **멈춘다.** 그 뒤의 로그는 처리하지 않는다.
   - `RUN_START`의 값이 서버 계산과 다른 것은 실패가 아니다(의심 표시만).
3. 거부된 로그 직전까지 적용한 결과를 저장하고 응답한다.
4. 서버는 받은 로그를 결과(적용·거부·건너뜀·미처리)와 함께 보관한다(감사·진단용).
5. **멱등성은 seq로 보장한다.** 같은 로그를 다시 보내도 이미 반영된 seq는 건너뛰므로 이중으로 적용되지 않는다.

### 한도

| 항목 | 값 |
|---|---|
| 요청당 로그 수 | 500개 이하 |
| 요청 본문 | 1 MB 이하 |

한도를 넘으면 `413 PAYLOAD_TOO_LARGE`다. 클라이언트는 쌓인 로그가 한도를 넘으면 여러 요청으로 나눠 **순서대로** 보낸다.

### 로그 거부 코드

| code | 타입 | 의미 |
|---|---|---|
| `SEQ_GAP` | 공통 | seq가 `lastAppliedSeq + 1`보다 큼(앞 로그 누락) |
| `MALFORMED_LOG` | 공통 | 필드 누락, 알 수 없는 `type`, 값 범위 오류 등 로그 형식 오류 |
| `CONTENT_VERSION_MISMATCH` | 공통 | `contentVersion`이 서버 콘텐츠 버전과 다름. 클라이언트는 이번 실행을 오프라인으로 바꾸고 업데이트 필요를 알린다 |
| `UNKNOWN_NODE` | `NODE_PURCHASE` | 서버의 노드 콘텐츠에 없는 노드 |
| `MAX_RANK_REACHED` | `NODE_PURCHASE` | 이미 최대 Rank |
| `NODE_HIDDEN` | `NODE_PURCHASE` | 아직 드러나지 않은 노드 |
| `NOT_ENOUGH_GOLD` | `NODE_PURCHASE` | Gold 부족 |
| `RANK_MISMATCH` | `NODE_PURCHASE` | `rank`가 서버의 현재 Rank + 1이 아님 |
| `COST_MISMATCH` | `NODE_PURCHASE` | `cost`가 서버의 비용과 다름 |
| `DUPLICATE_RUN_ID` | `RUN_START` | 이미 쓰인 `runId` |
| `RUN_NOT_OPEN` | `SETTLEMENT` | 열린 판이 없거나 `runId`가 열린 판과 다름(S1) |
| `SETTLEMENT_INVALID_KILLS` | `SETTLEMENT` | 이번 판에서 불가능한 장부 항목(S2) |
| `SETTLEMENT_EXP_MISMATCH` | `SETTLEMENT` | EXP 또는 Level·이정표가 장부와 맞지 않음(S3, S4) |
| `SETTLEMENT_GOLD_MISMATCH` | `SETTLEMENT` | Gold 또는 치명 보너스가 장부와 맞지 않음(S5, S6) |
| `SETTLEMENT_TIME_EXCEEDED` | `SETTLEMENT` | 시간 추가 횟수나 경과 시간이 가능한 범위를 넘음(S7, S8) |
| `SETTLEMENT_RESULT_MISMATCH` | `SETTLEMENT` | 결산 금액이나 결산 후 성장도가 서버 계산과 다름(S9) |

요청 전체의 형식 오류(잘못된 JSON, `logs` 누락, `seq`를 읽을 수 없거나 요청 안에서 1씩 증가하지 않음)는 로그 거부가 아니라 `400 INVALID_REQUEST`다. 그 밖의 로그 하나의 형식 오류는 그 로그의 `MALFORMED_LOG`다(계약 3.4).

### 클라이언트 처리 흐름

```
행위(판 시작, 결산, 구매, 새 게임)
          : 로컬 진행에 즉시 적용 → 로그를 진행도와 같은 원자적 쓰기로 저장(seq = 마지막 seq + 1)
            → 온라인이면 곧바로 POST /me/sync, 오프라인이면 쌓아 둔다
앱 시작    : (백그라운드) 로그인 → 쌓인 로그를 POST /me/sync(없으면 빈 배열) → 응답의 progress로 게임 시작
            이 과정이 끝나거나 타임아웃이 날 때까지 타이틀의 시작 버튼을 막는다
응답 처리  : appliedThroughSeq 이하의 대기 로그를 지운다
            rejected가 있으면 남은 대기 로그를 모두 폐기하고, 사용자에게 텍스트로 알린다
            progress로 로컬 진행도를 덮어쓴다
            다음 seq = appliedThroughSeq + 1
            rejected가 없는데 progress가 로컬과 다르면 서버 값을 따르고 경고를 남긴다(규칙 이식 버그)
실패       : 네트워크 오류, 5xx → 로그를 쌓아 두고 이번 실행은 오프라인으로 진행
            401 UNAUTHORIZED → 토큰 갱신 후 같은 요청을 한 번 재시도
            413 → 나눠서 순서대로 재전송
```

- 1단계에서는 **앱 시작 시에만** 연결을 시도한다. 시작 때 오프라인이었으면 그 실행 동안은 연결하지 않는다. 안전한 지점(앱 재개, 타이틀·업그레이드 화면)에서의 재연결은 2단계에서 추가한다.
- 첫 실행부터 오프라인이면 계정 없이 플레이한다. 처음 연결될 때 게스트 등록을 하고, seq 1부터 쌓인 로그를 모두 보낸다.
- 응답을 받기 전에는 대기 로그를 지우지 않는다. 응답을 못 받아 다시 보내도 seq 규칙으로 이중 적용되지 않는다.
- 판 도중 앱이 종료되어 결산 로그가 없는 판은, 다음 `RUN_START` 또는 `RESET`이 오면 서버가 포기로 닫는다.
- **운영 규칙:** 콘텐츠를 바꾸면 클라이언트에서 내보내기 → 서버 저장소 커밋 → 서버 배포 → 클라이언트 배포 순서를 지킨다. 버전이 다른 로그는 거부된다.

## 미정 항목

1. **허용 오차.** S8의 float 허용 오차, `RUN_START` 비교의 허용 오차(A0).
2. **통계 검사(S10).** 초당 처치 수, 치명 확률, 시간 추가 확률의 이상치를 의심 표시로 남길지(W1-6).
