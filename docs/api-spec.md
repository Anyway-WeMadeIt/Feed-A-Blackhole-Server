# API 명세 (초안)

클라이언트(Unity)와 서버(Spring Boot)의 통신 계약이다. 변경은 이 문서를 먼저 고친 뒤 구현에 반영한다.

- 상태: **초안 v0.6 (범위 확정)** — 오프라인 허용 방향으로 개정했고, 10일 안에 구현할 범위로 줄였다. 방향과 배경은 [offline-sync-direction.md](offline-sync-direction.md), 작업과 분배는 [offline-sync-work-breakdown.md](offline-sync-work-breakdown.md), 구현하지 않기로 한 설계는 [offline-sync-design-archive.md](offline-sync-design-archive.md)에 있다.
- v0.4와의 차이: **노드 트리·구매 API, 전투 세션 API, 진행 저장·초기화 API를 폐기하고 동기화 API(`POST /me/sync`) 하나로 흡수했다.** 온라인 필수 전제가 없어졌다.
- 구현 현황: 인증 전체(게스트 등록·로그인, 토큰 갱신·로그아웃), `GET /me`, 진행 데이터(불러오기·저장·초기화), 공통 오류 응답이 구현되어 있다. 이 중 **저장(`PUT /me/progress`)과 초기화(`POST /me/progress/reset`)는 검증을 우회하는 경로이므로 제거한다.** 동기화 API는 구현 전이다.
- 전제: **오프라인 플레이를 허용한다.** 온라인 전용 기능은 없다.
- **구현하지 않는 것**(설계는 archive에 있다): 이벤트 단위 전투 검증(`trace`), 단일 기기 로그인, gzip과 분할 전송, 재시도 가능 거절, 콘텐츠 버전 이력, `/system/status`, 요청 제한(429). 랭킹과 도전과제는 조건부다([후속 API](#후속-api-예약)).

## 설계 원칙

- **클라이언트가 게임을 실행하고 로컬 진행도를 가진다.** 노드 구매, 전투, 새 게임 같은 행위는 로컬에서 바로 적용하고, 같은 내용을 **행위 로그**로 기록한다. 온라인이면 곧바로, 오프라인이면 쌓아 두었다가 연결되었을 때 서버에 제출한다.
- **서버는 정본을 가지고 로그를 검증한다.** 서버는 자기 상태에 로그를 순서대로 적용해 결과를 계산하며, 클라이언트가 주장하는 값(Gold, 결산 금액 등)은 **검증 대상일 뿐 정산에 쓰이지 않는다.**
- **서버가 계산한 상태가 항상 이긴다.** 모든 동기화 응답에 서버 상태가 담기고, 클라이언트는 그것을 채택한다.
- **콘텐츠는 같은 정보가 양쪽에 있고 일치해야 한다.** 전투 콘텐츠는 Unity 에셋이 정본이고 JSON으로 내보내 서버 저장소에 둔다. `battle` 엔트리는 자신이 쓴 콘텐츠의 **번들 해시**(`contentVersion`)를 선언하고, 서버의 해시와 다르면 거절한다. 노드 콘텐츠는 [node-content.md](node-content.md)를 따른다.
- **전투 검증은 L1까지다.** 시작 스냅샷 대조, 처치 장부, 결산 규칙, 공급 상한, 시간 상한을 본다. 위치와 관련된 것(타격 범위, 생성 위치)과 전투 중 이벤트는 검증하지 않는다.
- **조작을 불가능하게 만드는 것이 목표가 아니다.** 규칙 위반과 비현실적인 값을 걸러 조작의 비용을 올리는 것이 목표이고, 유리한 시드를 고르는 정도는 수용한다.
- **거절 사유는 대분류 코드만 알린다.** 상세 사유는 서버 로그에만 남긴다.
- 노드 이름·아이콘·표시 문자열 같은 **표현 데이터는 클라이언트 소유**다. 서버는 `nodeId`·`statId` 같은 키만 다룬다.

## 공통 규칙

| 항목 | 규칙 |
|---|---|
| 기본 경로 | `/api/v1` |
| 형식 | JSON, UTF-8 |
| 인증 | `Authorization: Bearer <accessToken>` (`/auth/**` 제외) |
| 클라이언트 버전 | 모든 요청에 `X-Client-Version` 헤더. 서버가 지원하지 않는 버전이면 `426 Upgrade Required` |
| Gold | JSON 정수(int64). 문자열로 감싸지 않는다. 금액에 실수형을 쓰지 않는다. |
| 시각 | UTC, ISO-8601 |

### 오류 응답

```json
{ "error": { "code": "SYNC_IN_PROGRESS", "message": "..." } }
```

클라이언트는 `code`로 분기하고 `message`는 디버깅용으로만 쓴다. 이 형식은 **요청 단위 오류**에만 쓴다. 동기화에서 엔트리가 규칙에 어긋난 것은 오류가 아니라 정상 응답의 `result`로 알린다([동기화](#동기화)).

| 상태 코드 | 용도 |
|---|---|
| 400 | 입력 형식 오류 |
| 401 | 인증 실패 / 토큰 만료 |
| 404 | 없는 리소스 |
| 409 | 요청 충돌(같은 계정의 동시 동기화) |
| 413 | 요청이 한도를 넘음 |
| 426 | 지원하지 않는 클라이언트 버전 |

현재 정의된 `code`:

| code | 상태 | 의미 |
|---|---|---|
| `INVALID_REQUEST` | 400 | 필수 필드 누락·길이 초과, 잘못된 JSON 등. 필드 검증 실패는 `message`에 필드명이 담긴다. |
| `INVALID_CREDENTIALS` | 401 | 로그인 정보가 맞지 않음. |
| `UNAUTHORIZED` | 401 | 인증이 필요한 API에 액세스 토큰이 없거나, 만료·위조 등으로 유효하지 않음. 클라이언트는 리프레시 토큰으로 갱신을 시도한다. |
| `INVALID_REFRESH_TOKEN` | 401 | 리프레시 토큰이 없는 값이거나 만료·폐기됨. 클라이언트는 `guestId`·`guestSecret`으로 다시 로그인한다. |
| `SYNC_IN_PROGRESS` | 409 | 같은 계정의 다른 동기화 요청을 처리하는 중. 잠시 뒤 같은 요청을 다시 보낸다. |
| `PAYLOAD_TOO_LARGE` | 413 | 요청의 엔트리 수나 본문 크기가 한도를 넘음([한도](#한도)). |
| `NOT_FOUND` | 404 | 없는 경로. |
| `METHOD_NOT_ALLOWED` | 405 | 경로는 있으나 지원하지 않는 HTTP 메서드. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 지원하지 않는 `Content-Type`. |
| `INTERNAL_ERROR` | 500 | 서버 내부 오류. 상세는 서버 로그에만 남기고 응답에는 담지 않는다. |

v0.4의 `STALE_PROGRESS`, `PROGRESS_REGRESSION`은 저장 API와 함께 폐기되었다.

## 인증

토큰 없이 호출한다(`/auth/**` 전체. 이외의 API는 모두 액세스 토큰이 필요하다). 계정 모델은 `account` / `auth_identity` / `refresh_token` 테이블(`V1__create_auth_tables.sql`)을 따른다. 게스트의 `guestId`는 `auth_identity.identifier`, `guestSecret`은 `secret_hash`(SHA-256)로 저장된다.

| | 경로 | 설명 |
|---|---|---|
| POST | `/auth/guest/register` | 새 게스트 계정을 만들고 `guestId`, `guestSecret`을 발급한다. 계정 생성 시 진행 데이터를 초기값(Gold 0, 시작 성장도, `revision` 0)으로 만든다. |
| POST | `/auth/guest/login` | `{guestId, guestSecret}`으로 로그인하고 `accessToken`, `refreshToken`을 받는다. |
| POST | `/auth/refresh` | 리프레시 토큰으로 새 토큰 쌍을 받는다. 토큰은 사용할 때마다 교체(회전)된다. |
| POST | `/auth/logout` | 리프레시 토큰을 폐기한다. |
| GET | `/me` | (인증 필요) 액세스 토큰이 가리키는 계정. 토큰이 유효한지 확인하는 용도로도 쓴다. |

- `guestSecret`은 클라이언트가 안전하게 저장한다. 서버는 해시만 보관하므로 분실하면 복구할 수 없다.
- 소셜 로그인 연동은 `auth_identity`에 타입을 추가하는 방식으로 나중에 `/auth/link` 등을 열 수 있다.
- 기기 분실이나 재설치로 자격과 **아직 동기화하지 않은 로그**를 잃는 것은 수용한다. 모바일에서는 빈도가 높다.
- 같은 계정이 두 기기에 있으면(백업 복원이나 복제) 두 기기가 갈라져 진행할 수 있고, 이는 동기화의 `BASE_MISMATCH`로 정리된다([판정 규칙](#판정-규칙)). 단일 기기 로그인은 구현하지 않는다.

### POST `/auth/guest/register`

요청 본문은 없다.

```json
// 201 Created
{ "guestId": "ccd9b782-14b3-4a62-a004-22951ad0eeb3", "guestSecret": "O4uOMSZI3-pBa3mb8aSS2xRuxzTT02AX3IglV4dxPSk" }
```

- `guestId`: 서버가 만든 랜덤 UUID. 계정을 구분하는 값이며 비밀이 아니다(로그·문의에 써도 된다).
- `guestSecret`: 서버가 만든 256비트 랜덤 값(base64url, 43자). **이 응답에서만 원본을 볼 수 있다.** 서버는 SHA-256 해시만 저장하므로 클라이언트가 안전하게 보관해야 하고, 분실하면 복구할 수 없다.
- 호출할 때마다 새 계정이 만들어진다. 클라이언트는 저장된 자격증명이 없을 때(최초 실행)에만 호출한다.

### POST `/auth/guest/login`

```json
// 요청 (두 필드 모두 필수. guestId ≤ 255자, guestSecret ≤ 128자)
{ "guestId": "ccd9b782-14b3-4a62-a004-22951ad0eeb3", "guestSecret": "O4uOMSZI3-..." }

// 200 OK
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9...", "expiresIn": 900, "refreshToken": "kObw44EmutxdPuBKFcIkN7eKsLc1amB79wSA128ucWU" }
```

- 로그인에 성공하면 계정의 `last_login_at`이 갱신된다.
- `guestId`가 없는 경우와 `guestSecret`이 틀린 경우는 **같은 `401 INVALID_CREDENTIALS` 응답**이다. 존재하는 `guestId`를 알아낼 수 없게 하기 위함이다.
- 입력 형식 오류(빈 값, 길이 초과, 잘못된 JSON)는 `400 INVALID_REQUEST`.
- 로그인할 때마다 새 리프레시 토큰이 발급되므로 기기 여러 대에서 같은 계정을 쓸 수 있다.

### 토큰

로그인과 갱신은 같은 형식의 응답(`TokenResponse`)을 돌려준다.

| 필드 | 설명 |
|---|---|
| `accessToken` | API 호출에 쓰는 JWT(HS256). `Authorization: Bearer <accessToken>` 헤더로 보낸다. 수명 15분. 담는 값은 발급자(`iss`), 계정 ID(`sub`), 발급·만료 시각(`iat`, `exp`)뿐이다. 서버가 상태를 저장하지 않아 발급 후 취소할 수 없다. |
| `expiresIn` | 액세스 토큰이 몇 초 뒤 만료되는지(현재 900). 클라이언트가 갱신 시점을 정하는 데 쓴다. |
| `refreshToken` | 새 액세스 토큰을 받는 데 쓰는 불투명한 랜덤 값(43자). 수명 30일. **한 번 쓰면 폐기되므로 응답의 새 값으로 교체해 저장해야 한다.** 서버에는 해시만 저장된다. |

클라이언트 처리 흐름:

```
앱 시작   : 저장된 guestId가 없으면(최초 실행) register → login
            있으면, 리프레시 토큰이 있으면 POST /auth/refresh (401 INVALID_REFRESH_TOKEN이면 login), 없으면 login
API 호출  : 401 UNAUTHORIZED          → POST /auth/refresh 로 새 토큰을 받아 같은 요청을 한 번 재시도
            POST /auth/refresh → 401 INVALID_REFRESH_TOKEN → guestId·guestSecret으로 다시 로그인한 뒤 재시도
오프라인  : 로그인과 갱신은 연결되었을 때만 한다. 오프라인 플레이는 토큰 없이 진행하고, 연결되면 위 흐름을 거쳐 동기화한다.
```

- 기기에 저장하는 값은 `guestId`, `guestSecret`, `refreshToken`이다. 액세스 토큰은 메모리에만 두어도 된다(앱을 켜면 위의 "앱 시작"으로 다시 받는다).
- 갱신 응답의 새 `refreshToken`은 **받는 즉시 저장한다.** 옛 토큰은 이미 폐기됐다.
- `401 UNAUTHORIZED`로 거부된 요청은 서버가 처리하지 않았으므로 재시도해도 이중으로 적용되지 않는다.
- 갱신 요청은 한 번에 하나만 보낸다. 같은 토큰으로 겹쳐 보내면 한쪽은 `401`이고, 그 재사용 감지 때문에 이긴 쪽이 받은 새 토큰도 폐기된다.
- 이 흐름의 실행 가능한 기준 구현은 `src/test/java/org/example/feedablackhole/support/FakeClient.java`이고, 동기화 API가 구현되면 새 흐름(오프라인, 분기, 거절)을 같이 검증하도록 고친다.

### POST `/auth/refresh`

```json
// 요청
{ "refreshToken": "kObw44EmutxdPuBKFcIkN7eKsLc1amB79wSA128ucWU" }

// 200 OK — 로그인 응답과 같은 형식. 쓴 리프레시 토큰은 폐기되고 새 값이 들어 있다.
{ "accessToken": "...", "expiresIn": 900, "refreshToken": "새 값" }
```

- 이미 쓴(폐기된) 리프레시 토큰이 다시 들어오면 토큰이 복사되어 쓰이는 것으로 보고 **`401 INVALID_REFRESH_TOKEN`으로 거부하고, 그 계정의 모든 리프레시 토큰을 폐기한다.** 정상적으로 새로 받은 토큰도 무효가 되므로 사용자는 다시 로그인해야 한다. 응답을 받지 못한 채 같은 요청을 다시 보내는 경우에도 이렇게 되므로, 클라이언트는 갱신 요청을 동시에 여러 번 보내지 않는다.
- 없는 토큰, 만료된 토큰, 폐기된 토큰은 모두 같은 `401 INVALID_REFRESH_TOKEN`이다.
- 같은 토큰으로 동시에 들어온 요청은 서버가 한 번에 하나씩 처리한다.

### POST `/auth/logout`

```json
// 요청
{ "refreshToken": "kObw44EmutxdPuBKFcIkN7eKsLc1amB79wSA128ucWU" }

// 204 No Content
```

- 해당 리프레시 토큰을 폐기한다. 이미 폐기됐거나 없는 토큰이어도 `204`이다(반복 호출 가능, 토큰의 존재 여부도 알리지 않는다).
- 이미 발급된 액세스 토큰은 만료(최대 15분)까지 유효하다. 클라이언트는 로그아웃 시 저장한 토큰을 모두 지운다.

### GET `/me`

```json
// 200 OK
{ "accountId": 3 }
```

- 액세스 토큰이 없거나 유효하지 않으면 `401 UNAUTHORIZED`이며 `WWW-Authenticate: Bearer` 헤더가 붙는다.

## 진행 데이터

계정의 진행 상태(Gold, 블랙홀 성장도, 산 노드와 Rank)의 **서버 정본**이다. 계정은 요청이 아니라 토큰에서 정해진다. 계정을 만들 때 처음 상태(Gold 0, 성장도 0, 산 노드 없음, `revision` 0)가 함께 만들어진다. 진행 상태를 바꾸는 방법은 동기화뿐이다.

```json
{
  "revision": 14,
  "gold": 1780000,
  "growthStage": 3,
  "nodes": [ { "nodeId": "timer-01", "rank": 1 }, { "nodeId": "timer-02", "rank": 2 } ],
  "updatedAt": "2026-10-12T06:38:15.680980Z"
}
```

| 필드 | 설명 |
|---|---|
| `revision` | 서버가 지금까지 **적용한 로그 엔트리의 수**다. 엔트리가 적용될 때마다 1 오른다(새 게임 `reset` 엔트리도 센다). 동기화할 때 `baseRevision`으로 되돌려 보낸다. |
| `gold` | Gold. int64(0 이상). |
| `growthStage` | 블랙홀의 성장도(도달한 이정표 수, 0 이상). |
| `nodes` | 산 노드와 산 Rank. 산 순서(처음 산 순서)이고, 사지 않은 노드는 없다. |
| `updatedAt` | 마지막으로 바뀐 시각(UTC). |

### GET `/me/progress`

서버의 진행 상태를 불러온다. 새 계정은 처음 상태를 돌려준다. 새 기기에서 처음 받을 때나 서버 상태를 확인할 때 쓴다(엔트리 없는 동기화로도 같은 상태를 받는다).

## 동기화

### POST `/me/sync`

클라이언트가 쌓은 **행위 로그 묶음**을 서버에 제출한다. 서버는 자기 상태에 엔트리를 **순서대로 적용하며 검증**하고, 결과와 서버 상태를 돌려준다.

```json
// 요청
{
  "batchId": "7f0c2a64-5b1e-4c0a-9d11-3a2b9f6e1c77",
  "baseRevision": 12,
  "entries": [
    { "type": "purchase", "nodeId": "timer-01", "rank": 1 },
    { "type": "battle", "contentVersion": "a1b2c3d4e5f60718", "battleId": "…", "seed": 1830442981,
      "start":  { "growthStage": 2, "upgrades": { "timer": 14, "growth.time": 0 }, "supply": [ { "kind": "Asteroid", "count": 20 } ] },
      "result": { "elapsed": 74.2, "ended": "TimeUp",
                  "kills": [ { "kind": "Asteroid", "tier": 0, "size": 1, "golden": false, "count": 120 },
                             { "kind": "Asteroid", "tier": 1, "size": 2, "golden": true,  "count": 4, "goldenCrits": 1 } ],
                  "earnedGold": 530000, "settledGold": 530000, "level": 5, "exp": 8120, "growthStageAfter": 3 } },
    { "type": "reset" }
  ],
  "claimed": { "gold": 0, "growthStage": 0, "nodes": [] }
}

// 200 OK
{
  "result": "ACCEPTED",
  "applied": 3,
  "revision": 15,
  "rejected": null,
  "claimMatched": true,
  "state": { "revision": 15, "gold": 0, "growthStage": 0, "nodes": [], "updatedAt": "2026-10-12T06:40:02.113000Z" }
}
```

### 요청 필드

| 필드 | 설명 |
|---|---|
| `batchId` | 묶음의 UUID. 재시도할 때 같은 값을 쓰는 **멱등 키**다. |
| `baseRevision` | 이 로그가 시작된 서버의 `revision`, 즉 **마지막으로 확인한 값**이다. |
| `entries` | 행위 로그. 일어난 순서다. 비어 있어도 된다(서버 상태를 받는 용도). |
| `claimed` | (선택) 클라이언트가 엔트리를 모두 적용한 뒤의 상태. 서버가 계산한 결과와 비교해 `claimMatched`로 알린다. 클라이언트와 서버의 규칙이 어긋나는 것을 찾는 진단용이며, **정산에는 쓰이지 않는다.** |

### 엔트리

모든 엔트리는 `type`을 가진다.

**`purchase`: 노드 구매 (다음 Rank 하나)**

| 필드 | 설명 |
|---|---|
| `nodeId` | 산 노드 |
| `rank` | **구매 후의 Rank.** 서버의 현재 Rank + 1과 같아야 한다 |

서버는 노드가 서버의 노드 콘텐츠([node-content.md](node-content.md))에 있는지, 드러나 있는지(해금 조건), 최대 Rank를 넘지 않는지, 그 시점의 Gold가 비용 이상인지를 확인하고 Gold를 차감한다.

**`battle`: 한 판의 시작과 결산**

한 판은 **결산 시점에 엔트리 하나**로 만든다. 시작만 있고 결산이 없는 반쪽 로그는 없다. 전투 중 앱이 종료되거나 포기한 판은 기록하지 않는다.

| 필드 | 설명 |
|---|---|
| `contentVersion` | 이 판이 쓴 전투 콘텐츠의 번들 해시. 서버의 해시와 다르면 `CONTENT_VERSION_MISMATCH` |
| `battleId` | 클라이언트가 만든 UUID. 분석과 진단용 |
| `seed` | 난수 시드 |
| `start.growthStage` | 시작 때의 성장도. 서버 상태와 같아야 한다 |
| `start.upgrades` | 시작 때 쓴 완성된 스탯 표(`statId` → 값). 서버가 노드 Rank에서 계산한 표와 대조한다 |
| `start.supply` | 시작 때 공급한 적 구성(종류와 수, 변환 반영). 서버가 계산한 구성과 대조한다 |
| `result.elapsed` | 판의 경과 시간(초) |
| `result.ended` | 종료 사유: `TimeUp`(제한 시간), `Milestone`(이정표 도달), `Manual`(직접 종료) |
| `result.kills` | 처치 내역. 항목은 `{ kind, tier, size, golden, count, goldenCrits }`: 적 종류, 색 등급(Tier), 크기, 황금 여부, 처치 수, 황금 보너스 치명타 수(황금일 때만, 기본 0) |
| `result.earnedGold` | 처치 보상의 합 |
| `result.settledGold` | 결산 금액(이정표 도달 시 목표 잔액까지 채우는 보상 포함) |
| `result.level`, `result.exp` | 도달한 블랙홀 Level과 EXP |
| `result.growthStageAfter` | 결산 후 성장도 |
| `stats` | (선택) 스킬별 피해 같은 참고용 통계. 검증에 쓰지 않는다 |

`kills`가 이 형식인 이유: 같은 소행성도 Tier에 따라 Gold가 1에서 7776까지 6배씩 차이 나고, 크기 k는 Gold와 EXP에 k배, 황금은 Gold 배율과 보너스 치명타가 붙는다. 종류별 수량만으로는 Gold와 EXP의 장부를 대조할 수 없다.

서버는 결산을 **직접 계산**한다. `earnedGold`, `exp`, `level`, `settledGold`, `growthStageAfter`는 검증 대상이며, 서버가 계산한 값과 다르면 거절한다. 검증은 다음을 본다([작업 분해의 C3](offline-sync-work-breakdown.md#c-전투-l1-검증) 참고).

1. 시작 스냅샷: 성장도, 스탯 표, 시작 공급이 서버 계산과 같다(`BATTLE_START_MISMATCH`)
2. 장부: Gold와 EXP가 kills의 합과 같고 Level이 EXP 사다리에서 도출된다
3. 결산: 이정표 보상 규칙과 성장도
4. 공급 상한: 처치 수가 생성된 적의 수(시작 + 레벨업 성장 공급 + 재생성)와 색 분포의 허용 범위 안이다
5. 시간 상한: `elapsed`가 제한 시간(기본 + 성장 + 시간 추가 최대치) 안이고, 마지막 동기화 이후의 누적이 서버 시계의 경과를 넘지 않는다
6. 혜성 처치 수가 출현 주기의 상한 안이다

3~6번을 어기면 `BATTLE_IMPLAUSIBLE`이다.

**`reset`: 새 게임**

추가 필드가 없다. Gold, 성장도, 산 노드를 처음 상태로 되돌린다. `revision`은 계속 오른다. 되돌릴 수 없는 동작이므로 확인 절차(UI)는 클라이언트가 맡는다.

### 응답 필드

| 필드 | 설명 |
|---|---|
| `result` | `ACCEPTED`(모두 적용), `PARTIAL`(앞부분만 적용하고 이후는 거절), `BASE_MISMATCH`(기준이 달라 아무것도 적용하지 않음) |
| `applied` | 앞에서부터 적용된 엔트리 수 |
| `revision` | 적용 후 서버의 `revision`. **`baseRevision + applied`와 같다.** 클라이언트가 검산할 수 있다 |
| `rejected` | `PARTIAL`일 때만 있다: `{ "index": 2, "code": "NOT_ENOUGH_GOLD" }`. `index`는 거절된 엔트리의 위치(0부터) |
| `claimMatched` | 요청에 `claimed`가 있었을 때만 있다. 서버가 계산한 결과와 같으면 `true` |
| `state` | **항상 담긴다.** 이 응답 이후의 서버 정본([진행 데이터](#진행-데이터)와 같은 형식) |

### 판정 규칙

1. 엔트리는 **배열 순서대로** 적용한다. 각 엔트리는 직전 엔트리까지 적용된 상태에서 검증한다.
2. **`baseRevision`이 서버의 현재 `revision`과 다르면 `BASE_MISMATCH`**다. 엔트리는 적용하지 않고 서버 상태만 돌려준다. 다른 기기가 먼저 진행했거나 낡은 백업을 복원했거나, 단순히 서버 상태를 받고 싶어 엔트리 없이 보낸 경우가 모두 여기에 해당한다. 같은 계정에서 갈라진 로그가 둘 오면 **먼저 도착한 쪽이 채택**되고 나머지는 이 결과를 받는다.
3. 엔트리가 거절되면 **첫 무효 엔트리 직전까지만 적용**하고 `PARTIAL`로 응답한다. 거절된 엔트리와 그 뒤의 엔트리는 적용하지 않는다(로그가 앞 로그에 의존하기 때문이다).
4. **모든 거절은 영구적이다.** 클라이언트는 거절된 엔트리와 그 뒤의 **대기 로그를 모두 폐기**하고 `state`를 채택한다. 폐기한 로그는 진단을 위해 로컬에 보관할 수 있고, 무엇이 왜 취소되었는지 사용자에게 알려야 한다. `BASE_MISMATCH`도 같다(대기 로그를 폐기하고 `state`를 채택).
5. 서버가 계산한 상태가 항상 정본이다. 클라이언트는 응답의 `state`를 채택하고, 이 묶음에 들어 있지 않은 대기 로그가 남아 있으면 그 위에 **로컬에서 다시 적용**한다(`claimMatched`가 `true`이면 로컬 상태가 이미 같다).
6. **멱등:** 같은 `batchId`가 다시 오면 처음 응답을 그대로 돌려준다. 서버는 계정별로 **마지막 묶음의 응답 하나**를 보관한다. 클라이언트는 응답을 받을 때까지 **같은 묶음(같은 `batchId`, 같은 엔트리)을 그대로 재시도**하고, 그동안 쌓인 새 엔트리는 다음 묶음에 넣는다. 보관 범위를 벗어난 옛 `batchId`는 `BASE_MISMATCH`가 된다.
7. 같은 계정의 동기화는 **한 번에 하나**씩 처리한다. 처리 중에 다른 동기화가 오면 `409 SYNC_IN_PROGRESS`다.
8. 클라이언트가 보낸 시각은 신뢰하지 않는다. 시간과 관련된 검사(전투 `elapsed`의 합이 마지막 동기화 이후 서버 시계의 경과를 넘지 않는지)는 **서버 시계**로 한다. 서버는 계정별 마지막 동기화 시각을 저장한다.
9. 거절 사유는 아래 대분류 코드만 알린다. 상세 사유는 서버 로그에만 남긴다.

### 한도

| 항목 | 값 |
|---|---|
| 묶음당 엔트리 수 | 50개 이하 |
| 요청 본문 | 1 MB 이하 |

한도를 넘으면 `413 PAYLOAD_TOO_LARGE`다. 오프라인으로 쌓인 로그가 50개를 넘으면 클라이언트가 **여러 묶음으로 나눠 순서대로** 보낸다. 각 묶음의 `baseRevision`은 직전 응답의 `revision`이다.

### 엔트리 거절 코드

| code | 의미 |
|---|---|
| `UNKNOWN_NODE` | 서버의 노드 콘텐츠에 없거나 트리에 배치되지 않은 노드 |
| `NODE_LOCKED` | 아직 드러나지 않은(해금 조건을 만족하지 않은) 노드 |
| `MAX_RANK_REACHED` | 이미 최대 Rank |
| `RANK_MISMATCH` | `rank`가 서버의 현재 Rank + 1이 아님 |
| `NOT_ENOUGH_GOLD` | Gold 부족 |
| `CONTENT_VERSION_MISMATCH` | `battle`의 `contentVersion`이 서버의 번들 해시와 다름. 콘텐츠를 내보내지 않았거나 서버와 클라이언트의 배포 순서가 어긋난 경우 |
| `BATTLE_START_MISMATCH` | 시작 스냅샷(성장도, 스탯 표, 시작 공급)이 서버 계산과 다름 |
| `BATTLE_IMPLAUSIBLE` | 결산이 장부, 결산 규칙, 공급 상한, 시간 상한을 벗어남 |
| `MALFORMED_ENTRY` | 필드 누락, 알 수 없는 `type` 등 엔트리 형식 오류 |

요청 전체의 형식 오류(잘못된 JSON, 누락된 `batchId` 등)는 엔트리 거절이 아니라 `400 INVALID_REQUEST`다.

### 클라이언트 처리 흐름

```
행위(구매, 결산, 새 게임)
          : 로컬 진행에 즉시 적용 → 행위 로그에 영속 기록(구매와 결산마다 즉시, 원자적으로)
            → 온라인이면 곧바로 동기화(엔트리 하나), 오프라인이면 대기 로그에 쌓는다
동기화 시점: 앱 시작과 로그인 직후, 재접속 때, 오프라인 중 일정 주기의 연결 시도
            → 대기 로그를 묶음(≤ 50개)으로 나눠 순서대로 POST /me/sync
              (baseRevision = 마지막으로 확인한 revision, 이후 묶음은 직전 응답의 revision)
응답 처리  : ACCEPTED     → 확인한 revision 갱신, 대기 로그에서 applied만큼 제거, state 채택 후 남은 로그를 다시 적용
            PARTIAL       → state 채택, 대기 로그를 모두 폐기(로컬에 보관), 사용자에게 안내
            BASE_MISMATCH → state 채택, 대기 로그 폐기(로컬에 보관), 사용자에게 안내
            네트워크 오류, 5xx, 409 SYNC_IN_PROGRESS → 같은 batchId로 재시도
            413           → 묶음을 반으로 나눠 재시도
            401 UNAUTHORIZED → 토큰 갱신 후 같은 요청을 한 번 재시도
```

- 처음 설치한 기기나 로컬 진행이 없는 기기는 엔트리 없이 `POST /me/sync`(또는 `GET /me/progress`)로 서버 상태를 받는다.
- 로그가 `ACCEPTED`나 `PARTIAL`로 처리되었다는 응답을 받기 전에는 대기 로그에서 지우지 않는다.
- **운영 규칙:** 콘텐츠를 바꾸면 내보내기 → 서버 저장소 커밋 → **서버 배포 → 클라이언트 배포** 순서를 지킨다. 서버가 먼저 새 해시를 알아야 새 클라이언트의 `battle`이 거절되지 않는다.

## 후속 API (예약)

아래는 **조건부**로 5일차 진척을 보고 구현 여부를 정한다(구현하지 못하면 [archive](offline-sync-design-archive.md#7-후속-api-스케치)의 스케치가 기록으로 남는다). 랭킹과 도전과제는 **검증을 통과한 서버 상태에서 서버가 직접 계산**하며, 클라이언트가 점수나 달성 여부를 주장하는 방식은 쓰지 않는다.

| | 경로 | 설명 |
|---|---|---|
| POST | `/me/ranking` | 닉네임과 함께 랭킹 등록을 요청한다. 서버는 먼저 밀린 로그의 처리가 끝났는지 확인한 뒤 검증된 진행도로 점수를 계산한다 |
| GET | `/rankings` | 랭킹 조회 |
| GET | `/achievements` | 도전과제와 전체 유저의 달성률 |

## 흐름

```
로그인    : register(최초 1회) → login                 (온라인일 때만. 오프라인이면 로컬 진행으로 바로 플레이)
동기화    : POST /me/sync                              (대기 로그 제출, 응답의 state 채택)
플레이    : 구매, 전투를 로컬에서 진행하며 로그에 기록
            온라인이면 행위마다 POST /me/sync, 오프라인이면 쌓아 두었다가 재접속 때
```

## 미정 항목

1. **한도 값.** 묶음당 엔트리 수(50)와 본문 크기(1 MB)는 실제 엔트리 크기를 재어 조정한다.
2. **L1 검증의 여유 계수.** 공급 상한의 여유, 시간 상한의 여유는 실제 판의 L1 엔트리(골든 케이스)로 보정한다.
3. **후속 API.** 랭킹, 도전과제(조건부).
