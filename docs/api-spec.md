# API 명세 (초안)

클라이언트(Unity)와 서버(Spring Boot)의 통신 계약이다. 변경은 이 문서를 먼저 고친 뒤 구현에 반영한다.

- 상태: **초안 v0.2** — 인증, 진행 데이터, 노드 트리·구매, 전투 세션의 골격까지. 전투 이벤트 로그 형식은 미정이다([미정 항목](#미정-항목)).
- 구현 현황: 게스트 등록·로그인(`/auth/guest/*`)과 공통 오류 응답이 구현되어 있다. 토큰 발급(`accessToken`·`refreshToken`)·갱신·로그아웃, `/system/status`와 그 외 엔드포인트는 아직 구현 전이다.
- 전제: 온라인 필수, 리더보드는 범위 밖(기본 서버 완성 후 확장 검토).

## 설계 원칙

- **실시간 시뮬레이션은 클라이언트, 영속 상태와 경제(Gold·구매·진행도)의 최종 결정은 서버.** 클라이언트는 전투 결과를 증거와 함께 제출하고, 서버가 인정 여부를 판단한다.
- **메타 층(노드 트리·비용·효과·해금 규칙)은 서버 전용.** 클라이언트는 규칙을 구현하지 않고 서버가 계산한 완성품(노드 상태, 스탯 표)만 받아 그린다.
- **전투 층(적 스탯, 출현 규칙, 성장 구간 수치)은 1차에서는 클라이언트 빌드에 번들**하고, 세션 시작 때 `contentVersion` 일치만 확인한다. 이후 세션 시작 응답으로 조립 결과를 전달하는 방식으로 확장할 수 있도록 응답 구조에 여지를 둔다.
- 노드 이름·아이콘·표시 문자열 같은 **표현 데이터는 클라이언트 소유.** 서버는 `nodeId`·`statId`를 키로 값과 단위만 준다.

## 공통 규칙

| 항목 | 규칙 |
|---|---|
| 기본 경로 | `/api/v1` |
| 형식 | JSON, UTF-8 |
| 인증 | `Authorization: Bearer <accessToken>` (인증 섹션의 엔드포인트와 `/system/status` 제외) |
| 클라이언트 버전 | 모든 요청에 `X-Client-Version` 헤더. 서버가 지원하지 않는 버전이면 `426 Upgrade Required` |
| Gold | JSON 정수(int64). 문자열로 감싸지 않는다. 금액에 실수형을 쓰지 않는다. |
| 시각 | UTC, ISO-8601 |

### 오류 응답

```json
{ "error": { "code": "NOT_ENOUGH_GOLD", "message": "..." } }
```

클라이언트는 `code`로 분기하고 `message`는 디버깅용으로만 쓴다.

| 상태 코드 | 용도 |
|---|---|
| 400 | 입력 형식 오류 |
| 401 | 인증 실패 / 토큰 만료 |
| 404 | 없는 리소스 |
| 409 | 규칙 위반 (현재 상태와 충돌) |
| 422 | 전투 결과 검증 실패 |
| 426 | 지원하지 않는 클라이언트 버전 |

현재 정의된 `code` (엔드포인트가 추가되면 도메인별 코드를 이 표에 더한다):

| code | 상태 | 의미 |
|---|---|---|
| `INVALID_REQUEST` | 400 | 필수 필드 누락·길이 초과, 잘못된 JSON 등. 필드 검증 실패는 `message`에 필드명이 담긴다. |
| `INVALID_CREDENTIALS` | 401 | 로그인 정보가 맞지 않음. |
| `NOT_FOUND` | 404 | 없는 경로. |
| `METHOD_NOT_ALLOWED` | 405 | 경로는 있으나 지원하지 않는 HTTP 메서드. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 지원하지 않는 `Content-Type`. |
| `INTERNAL_ERROR` | 500 | 서버 내부 오류. 상세는 서버 로그에만 남기고 응답에는 담지 않는다. |

## 인증

토큰 없이 호출한다. 계정 모델은 `account` / `auth_identity` / `refresh_token` 테이블(`V1__create_auth_tables.sql`)을 따른다. 게스트의 `guestId`는 `auth_identity.identifier`, `guestSecret`은 `secret_hash`(SHA-256)로 저장된다.

| | 경로 | 설명 |
|---|---|---|
| POST | `/auth/guest/register` | 새 게스트 계정을 만들고 `guestId`, `guestSecret`을 발급한다. 계정 생성 시 진행 데이터를 초기값(Gold 0, 시작 성장도)으로 만든다(진행 데이터 도입 단계에서 함께 구현). |
| POST | `/auth/guest/login` | `{guestId, guestSecret}`으로 로그인하고 `accessToken`, `refreshToken`을 받는다(토큰은 구현 전, 아래 참고). |
| POST | `/auth/refresh` | 리프레시 토큰으로 새 토큰 쌍을 받는다. 토큰은 사용할 때마다 교체(회전)된다. |
| POST | `/auth/logout` | 리프레시 토큰을 폐기한다. |
| GET | `/system/status` | `{ "minClientVersion": "...", "maintenance": false }`. 인증 불필요. 시작 화면에서 호출한다. |

- `guestSecret`은 클라이언트가 안전하게 저장한다. 서버는 해시만 보관하므로 분실하면 복구할 수 없다.
- 소셜 로그인 연동은 `auth_identity`에 타입을 추가하는 방식으로 나중에 `/auth/link` 등을 열 수 있다.
- 기기 분실 시 진행 데이터를 잃는 것은 1차에서 수용한다.

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

// 200 OK — 토큰 발급 구현 전의 임시 형태
{ "accountId": 2 }
```

- 최종 응답은 `{ "accessToken": "...", "refreshToken": "..." }`이다(토큰 단계에서 `accountId` 대신 교체한다).
- 로그인에 성공하면 계정의 `last_login_at`이 갱신된다.
- `guestId`가 없는 경우와 `guestSecret`이 틀린 경우는 **같은 `401 INVALID_CREDENTIALS` 응답**이다. 존재하는 `guestId`를 알아낼 수 없게 하기 위함이다.
- 입력 형식 오류(빈 값, 길이 초과, 잘못된 JSON)는 `400 INVALID_REQUEST`.

## 진행 데이터

### GET `/me/progress`

```json
{ "gold": 1250000, "growthStage": 2 }
```

가벼운 조회이다. 노드 정보는 `/me/nodes`로 따로 받는다.

## 노드 트리

### GET `/me/nodes`

이 사용자에게 **드러난 노드만** 돌려준다.

```json
{
  "nodes": [
    {
      "id": "timer-01",
      "x": 3,
      "y": 5,
      "rank": 1,
      "maxRank": 1,
      "state": "Owned",
      "nextCost": null,
      "effects": [{ "statId": "timer", "value": 2, "unit": "Flat" }]
    }
  ],
  "links": [["timer-01", "timer-02"]]
}
```

| 필드 | 설명 |
|---|---|
| `state` | `Revealed`(보이지만 Gold 부족), `Purchasable`, `Owned`(최대 Rank까지 구매). `Hidden`은 내려가지 않는다. |
| `nextCost` | 다음 Rank의 비용. 최대 Rank면 `null`. |
| `effects` | 툴팁에 쓰는 다음 Rank의 효과. 값과 `unit`(`Flat`/`Percent`)만 담는다. 표시 문자열 조립과 지역화는 클라이언트가 한다. 최대 Rank면 마지막 Rank의 효과. |
| `x`, `y` | 격자 좌표. 드러난 노드만 내려가므로 숨겨진 노드의 위치는 노출되지 않는다. |
| `links` | 양 끝이 모두 응답에 포함된 노드인 선만 담는다. 선은 방향이 없다. |

### POST `/me/nodes/{nodeId}/purchase`

다음 Rank 하나를 산다.

```json
// 요청
{ "expectedRank": 1 }

// 응답
{
  "gold": 1249998,
  "node": { "...갱신된 노드(GET /me/nodes의 노드와 같은 형식)..." },
  "revealed": [ { "...새로 드러난 노드..." } ],
  "revealedLinks": [["timer-01", "timer-02"]]
}
```

- `expectedRank`는 "내가 사려는 Rank"이다. 서버의 현재 Rank + 1과 다르면 `409 STALE_RANK`이며, 응답에 최신 노드 상태를 함께 담는다. 더블클릭·재전송으로 같은 Rank를 두 번 사는 것을 막는다.
- 구매 응답에 변경분이 모두 있으므로 클라이언트는 재조회 없이 갱신한다.
- Gold 차감과 Rank 증가는 한 트랜잭션이다.

| 상황 | 응답 |
|---|---|
| 트리에 없는 노드 / 아직 드러나지 않은 노드 | `404 NODE_NOT_FOUND` (숨겨진 노드의 존재가 새지 않도록 같은 응답) |
| 이미 최대 Rank | `409 MAX_RANK_REACHED` |
| Gold 부족 | `409 NOT_ENOUGH_GOLD` |
| `expectedRank` 불일치 | `409 STALE_RANK` |

클라이언트의 `PurchaseResult` 열거형과의 대응: `UnknownNode`·`Hidden` → `NODE_NOT_FOUND`, `MaxRankReached` → `MAX_RANK_REACHED`, `NotEnoughGold` → `NOT_ENOUGH_GOLD`.

## 전투 세션

### POST `/battles` — 전투 시작

```json
// 요청
{ "contentVersion": "2026.10.1" }

// 응답
{
  "battleId": "…",
  "seed": 1830442981,
  "growthStage": 2,
  "upgrades": { "timer": 14, "growth.time": 0, "growth.asteroids": 10 }
}
```

- `upgrades`는 서버가 노드 Rank로 계산한 **완성된 스탯 표**(`statId` → 값)이다. 클라이언트는 노드 규칙을 모르고 이 값으로 전투를 조립한다.
- `contentVersion`이 서버의 전투 데이터 버전과 다르면 `409 CONTENT_VERSION_MISMATCH`.
- 진행 중인 세션이 이미 있으면 **자동으로 포기 처리**하고 새로 시작한다. 포기한 전투에서 번 Gold는 반영되지 않는다(클라이언트도 전투 중에는 저장하지 않는 규칙과 같다).
- 서버는 세션 시작 시점의 업그레이드 상태와 `contentVersion`을 고정해 기록한다.

### POST `/battles/{battleId}/result` — 결과 제출

```json
// 요청 (summary·events의 형식은 미정)
{ "playedSeconds": 74.2, "summary": { "...": "..." }, "events": [ "..." ] }

// 응답
{ "settledGold": 530000, "gold": 1780000, "growthStage": 3, "raisedStage": true }
```

- **정산 금액은 서버가 계산한다.** 이정표에 닿은 판은 목표 잔액과 서버가 아는 현재 Gold의 차액이고, 아니면 검증된 획득 Gold이다. 클라이언트가 주장하는 금액은 검증 대상일 뿐 정산에 쓰이지 않는다.
- 같은 `battleId`로 다시 제출하면 처음 결과를 그대로 돌려준다(멱등).
- 검증에 실패하면 `422 BATTLE_REJECTED`만 돌려주고 사유는 알리지 않는다. 사유는 서버 로그에 남긴다.
- 포기된 세션이나 다른 사용자의 세션이면 `404 BATTLE_NOT_FOUND`.

### 검증 단계 (서버 내부)

| 단계 | 내용 |
|---|---|
| A. 결과 합리성 | 시작 이후 실제 경과 시간 ≥ `playedSeconds`(일시정지가 있으면 실제가 더 길다). `playedSeconds` ≤ 제한 시간(타이머 스탯 + 성장 연장). Gold = 처치 수 × 적별 Gold. 이정표 보상 규칙 일치. |
| B. 이벤트 로그 추적 | 시각이 단조 증가. 처치 ≤ 생성. 레벨업·EXP가 처치에서 도출됨. 구간별 처치 속도가 업그레이드 스탯의 상한 안. |
| C. 부분 재현 (후순위) | 시드로 적 구성·출현을 서버가 재생성해 대조. |

의심 결과는 즉시 차단하지 않고 점수를 매겨 플래그만 남기거나 거부한다.

## 흐름

```
시작 화면 : GET /system/status
로그인    : register(최초 1회) → login
로비      : GET /me/progress, GET /me/nodes
노드 구매 : POST /me/nodes/{id}/purchase (반복)
전투      : POST /battles → (클라이언트 시뮬레이션) → POST /battles/{id}/result
            → 응답의 gold·growthStage로 로비 상태 갱신
```

## 미정 항목

1. **노드 배치 원본(좌표·선)의 위치.** 현재 Unity의 `NodeCatalog` 에셋과 CSV(`Assets/Data/NodeTable/*.csv`)에 있다. 서버가 같은 데이터를 읽어야 하므로 서버용 파일(JSON 등)로 내보내는 단계와 형식을 DB 스키마 설계 때 정한다.
2. **전투 이벤트 로그 형식.** 기록할 이벤트 종류와 시점, 크기 처리(gzip 본문 허용 또는 구간 집계)를 정한다.
3. **세션 만료 정책.** 끝나지 않은 세션을 폐기하는 타임아웃 값.
4. **콘텐츠 버전 배포 방식.** 클라이언트 번들 전투 데이터와 서버의 검증용 표를 단일 정본에서 배포하는 방법.
5. **2차 확장.** 세션 시작 응답에 전투 조립 결과(적 스탯 등)를 포함하는 방식으로 옮길지.
