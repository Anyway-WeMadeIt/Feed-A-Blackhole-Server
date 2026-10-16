# 서버 연동 작업지시

- 상태: **확정** (2026-10-12). 작업 정의와 설계는 [server-sync-plan.md](server-sync-plan.md)(이하 "계획서"), 구현 형식과 시그니처는 [server-sync-contracts.md](server-sync-contracts.md)(이하 "계약")에 있고, 이 문서는 **누가 무엇을 어떤 순서로** 하는지만 정한다.
- 인원 3명.

| 담당 | 역할 | 영역 |
|---|---|---|
| **A** (리드) | 데이터 구조·계약 설계와 결정, 판단이 필요한 핵심 로직 | 서버 + 클라이언트 |
| **B** | 명세대로 서버 구현 | 서버 |
| **C** | 명세대로 클라이언트 구현 | 클라이언트 |

- 규모 환산: S = 1일, M = 2.5일, L = 4.5일(인일). 작업 제목의 괄호는 A 작업이면 (영역, 일수), B·C 작업이면 (규모, 일수)다.

## 1. 진행 방식

1. **계약 우선**: A가 먼저 계약(데이터 형식, 스키마, 인터페이스)을 확정해 `dev`에 병합한다(0단계). B와 C는 그 계약만 보고 구현하므로 서로를 기다리지 않는다.
2. **경계는 인터페이스로**: 다른 담당의 결과물이 필요한 곳은 0단계에서 정한 인터페이스와 가짜 구현(fake)으로 먼저 개발하고, 통합 지점에서 실제 구현으로 바꾼다.
3. **계약 변경은 A만**: B와 C는 명세에 모호하거나 틀린 곳을 발견하면 A에게 알리고, A가 계약 문서와 코드를 고친다. 각자 임의로 형식을 바꾸지 않는다.
4. **0단계 중 B와 C는** 이미 확정된 계약(계약 1장)이나 계약이 필요 없는 작업부터 시작한다(B: W0-3 → W1-4, C: W2-1). 남은 계약은 아래 0단계 현황의 기한 안에 A가 채운다.

### 0단계(A0) 현황

| 계약 장 | 작업지시 ID | 상태 | 막는 작업 | 기한 |
|---|---|---|---|---|
| 1장 콘텐츠 | A0-2, A0-3 | **완료** | B의 W0-3·W1-4, A의 W0-1 | - |
| 2장 클라이언트 인증 경계, 타임아웃 | A0-6 일부, A0-7 일부 | **완료** | C의 W2-1·W2-2·W2-3 | - |
| 3장 로그·동기화 JSON | A0-1 | **완료** | B의 W1-3·W1-5, A의 W1-2·W3-2 | B 시작 후 5일 |
| 4장 서버 스키마, 처리기 인터페이스 | A0-4, A0-5 | **완료** | B의 W1-3·W1-5, A의 W1-2 | B 시작 후 5일 |
| 5장 클라이언트 로그·저장 경계, 나머지 수치 | A0-6, A0-7 | **완료** | C의 W3-1(`KillBucket`, 시작 후 5일)·W3-4, A의 W3-2·W3-3 | C 시작 후 5일 |

## 2. 담당별 작업 요약

| 담당 | 작업 | 합계 |
|---|---|---|
| A | A0(계약) 2, W0-1 2.5, W1-2 2.5, W1-6 2.5, W2-3 1, W3-0 0.5, W3-2 2.5, W3-3 2.5, W4-1a 1.5, W4-2b 1.5 | **19일** |
| B | W0-3 2.5, W1-4 4.5, W1-3 2.5, W1-5 1, W1-7 1, W1-8 2.5, W4-1b 1, W5-2 1 | **16일** |
| C | W2-1 2.5, W2-2 2.5, W3-1 2.5, W3-4 2.5, W3-5 1, W4-2a 2, W5-1 1 | **14일** (W0-2, W2-3, W3-0, W4-1a는 A가 진행) |

- B는 서버만, C는 클라이언트만 다룬다. 양쪽에 걸친 작업(W4-1, W4-2)은 서버 쪽과 클라이언트 쪽으로 나눴다(a = 클라이언트, b = 서버).
- 계획서의 W5-3(콘텐츠 버전 불일치, 보류)과 W6-1(재연결 2단계)은 이번 배분에서 제외한다. 1차 완료 후 A가 다시 배분한다.
- **2026-10-14 변경**: 클라이언트 `dev`가 시뮬레이션 도구(`Tools/Simulation`, `Assets/Scripts/Simulation/*`)를 삭제했다. 이 도구의 `BattleRunner`·`CampaignRunner`가 W3-1의 완료 조건과 W4-1a·W4-2a의 전제였으므로, 도구를 되살리지 않고 필요한 최소 부분을 에디터 도구로 새로 두기로 했다. W3-0을 신설하고(0.5일, A가 진행해 완료), W4-1a·W4-2a를 에디터 도구 기준으로 고쳤다(W4-2a 1일 → 2일). C 합계는 15.5일 → 16.5일, A 합계는 16일 → 16.5일이다. C가 길어지므로 W4-1a를 A로 옮길 수 있다(3단계 작업이라 일정 여유가 있다).
- **2026-10-14 변경(2)**: W2-3(연결 상태와 타이틀)을 C에서 A로 옮겨 완료했다. A 합계 16.5일 → 17.5일, C 합계 16.5일 → 15.5일이다. 위 일정 그림의 C 행 W2-3은 그대로 두었다(완료 표시만 이 문서의 작업 상세를 따른다).
- **2026-10-14 변경(3)**: A의 W3-2(로그 모델과 생성기), W3-3(저장 포맷 2)이 클라이언트 `dev`에 병합되어 **완료**다(PR #79, 병합 `6a0a42f`). 클라이언트 작업이라 문서 반영이 늦었다. 칸별 처치 집계(W3-1)는 아직이어서, 결산 로그의 `kills`는 그 전까지 비어 있다(작업 상세 W3-2). 이로써 C의 W3-4는 선행(W2-3, W3-2, W3-3)이 모두 풀렸다.
- **2026-10-14 변경(4)**: B의 W1-4(전투 규칙 이식)가 서버 `dev`에 병합되어 **완료**다(PR #20, 병합 `a171e1a`). B의 W1-5와 A의 W1-6은 실제 규칙으로 통합할 수 있고, W4-1b는 C의 W4-1a(기대값 표)만 남았다.
- **2026-10-15 변경(6)**: B의 W1-3(노드 구매·새 게임 처리기)이 서버 `dev`에 병합되어 **완료**다(PR #22). M1의 서버 쪽 조건(W1-2, W1-3)이 갖춰졌다.
- **2026-10-15 변경(7)**: B의 W1-5(판 시작 처리기)가 완료되었다(`feat/run-start`). 네 가지 로그 타입의 처리기가 모두 갖춰져 W1-8(E2E와 CI)을 시작할 수 있다.
- **2026-10-15 변경(8)**: B의 W1-7(옛 API 정리)이 완료되었다(`feat/remove-old-progress-api`). `revision` 열 제거(V4)는 A가 같은 브랜치에서 맡았다. W1-8은 `FakeClient`의 옛 메서드가 이미 없는 상태에서 시작한다.
- **2026-10-16 변경(9)**: C의 W3-4(동기화 전송기)가 완료되었다(클라이언트 `feat/asyncServer`). 리뷰에서 계약과 어긋난 부분(동기화 타임아웃, 오프라인 사유, 응답 seq 검사)을 고치고 테스트를 다시 썼다(작업 상세 W3-4). 로컬 서버로 M1 시나리오가 통과해 **M1 조건이 모두 갖춰졌다.** W1-6 남은 일 ②(결산 → 구매 로그 순서)도 함께 반영했다. `SyncSender`의 위치는 계약 5.2대로 Assembly-CSharp이 아니라 `BlackHole.Net`이다.
- **2026-10-16 변경(10)**: W4-1a(이식 기대값 표 생성)를 C에서 A로 옮겼다. C의 작업 속도를 고려한 결정이고, 이제 형식도 A가 정한다(계약 1.7a). A 합계 17.5일 → 19일, C 합계 15.5일 → 14일이다. 마무리 검증 단계이므로 W4-1a·W4-1b·W4-2a·W4-2b의 픽스처와 그 서버 테스트는 샘플이 아니라 **실제 콘텐츠**를 쓴다(계약 C30, C32). 그 전에 서버 콘텐츠 묶음을 클라이언트의 현재 콘텐츠로 갱신했다(`contentVersion` `4ab481c6a60d6e49`, PR #28). 노드 수는 276개에서 263개로, 수치 `supernovaStar.hpDamage`는 `supernovaStar.damage`로 바뀌었다.
- **2026-10-14 변경(5)**: C의 W3-1(칸별 처치 집계)이 클라이언트 `dev`에 병합되어 **완료**다(PR #88, 병합 `10cfa25`). 결산 로그의 `kills`·`goldenCritCount`·`goldenCritBonusGold`·`timeBonusCount`가 실제 값으로 채워진다. W4-2a의 선행(W3-0, W3-1, W3-2)이 모두 풀렸고, 클라이언트 쪽 M2 조건(W3-1)도 갖춰졌다.

## 3. 일정 개요

```
        0단계(계약)    1단계(병렬 구현)                     2단계(통합)          3단계(검증)
A  ▕ A0 ▏ W0-1 ▏ W1-2 ▏ W3-2 ▏ W3-3 ▏ W3-0 ▏ W1-6 ▏ W4-1a ▏─────── W4-2b ▏
B  ▕ W0-3 ▏ W1-4 (전투 규칙 이식) ▏ W1-3 ▏ W1-5 ▏ W1-7 ▏ W4-1b ▏ W1-8 ▏ W5-2 ▏
C  ▕ W2-1 ▏ W2-2 ▏ W2-3 ▏ W3-1 ▏ W0-2 ▏ W3-4 ▏ W3-5 ▏ W4-2a ▏ W5-1 ▏
```

| 확인 지점 | 조건 | 확인 내용 |
|---|---|---|
| **M0** 계약 확정 | A0 병합 | 3명이 계약 문서를 함께 읽고 질문을 해소한다. |
| **M1** 구매 통합 | W1-2, W1-3, W2-3, W3-2, W3-3, W3-4 | 실제 서버와 클라이언트로 로그인 → `RESET`·`NODE_PURCHASE` 전송 → 진행도 응답 반영이 된다. 오프라인으로 쌓은 구매 로그가 다음 실행에서 반영된다. |
| **M2** 전투 통합 | W1-4~6, W3-1, W0-1~3 | 판 시작·결산 로그가 실제로 검증·반영된다. 조작한 결산이 거부되고 클라이언트에 알림이 뜬다. |
| **M3** 완료 | W1-8, W4-*, W5-* | E2E·골든 테스트 통과, CI에서 테스트 실행. |

## 4. 작업 상세

각 작업은 **목표 / 참조 / 산출물 / 완료 조건 / 선행**으로 적는다. "계획서 n장"은 [server-sync-plan.md](server-sync-plan.md)의 장 번호다.

---

### A — 설계·핵심 로직

#### A0. 계약 확정 (2일, 최우선)

**목표**: B와 C가 서로 기다리지 않고 구현할 수 있도록 모든 경계를 확정한다.

**산출물**: 계약 문서 `docs/server-sync-contracts.md`(서버 저장소), 아래 코드와 샘플 파일을 각 저장소 `dev`에 병합한다.

| # | 계약 | 내용 | 주 사용자 |
|---|---|---|---|
| A0-1 | 로그·동기화 JSON | 계획서 5장을 필드 단위로 확정한다: 필드 이름·타입·필수 여부, `enemyId` 문자열 목록, `tier`(0부터)·`size`(1부터) 규칙, 요청당 최대 로그 수, **거부 코드 전체 목록**. 타입마다 JSON 샘플 파일을 만들어 양쪽 저장소에 같은 파일로 둔다(= W4-3). | B, C |
| A0-2 | 콘텐츠 묶음 형식 | **완료**(계약 1.2~1.4). 파일 구성, 버전 계산법, 샘플 묶음(`src/test/resources/content-sample/`). | B(W0-3), A(W0-1), C(W0-2) |
| A0-3 | 서버 콘텐츠 객체와 규칙 인터페이스 | **완료**(계약 1.5~1.7). `battle/content/` record, `battle/rules/` 인터페이스(`BattleRules`, `UpgradeValues`, `RunSetup`, `KindSetup`), 수치 일치 규칙. | B(W1-4, W0-3), A(W1-6) |
| A0-4 | DB 스키마 | V3 마이그레이션(계획서 8장)과 엔티티·리포지토리(= W1-1). | B, A |
| A0-5 | 서버 적용 엔진 인터페이스 | `LogHandler`(타입별 처리기) 인터페이스, 처리 결과 타입(적용 / 거부(코드) / 의심 표시), 처리기에 넘기는 진행도·판 상태 객체. | B(W1-3, W1-5), A(W1-2, W1-6) |
| A0-6 | 클라이언트 경계 | 코어의 칸별 처치 집계 타입(`KillBucket` 등) 시그니처, 로그 모델(C#) 시그니처, `ISyncApi`·`IAuthApi` 인터페이스, 저장 포맷 2의 대기 로그 구조 초안, 계정 정보 파일 형식. | C, A |
| A0-7 | 수치 결정 | 로그인 전체 타임아웃, 요청별 타임아웃, float 비교 허용 오차(S8, `RUN_START`의 제한 시간), 거부 알림 문구. | B, C |

**완료 조건**: 3명이 계약 문서를 리뷰하고(M0), 양쪽 저장소가 계약 코드를 포함한 상태로 빌드된다.

#### W0-1. 서버용 콘텐츠 내보내기 도구 (클라이언트, 2.5일) — 완료(서버 반영은 W0-3)

- **목표**: 클라이언트 에디터 메뉴 하나로 A0-2 형식의 묶음을 생성한다.
- **참조**: 계획서 4.1~4.2, `Battle/GameContentLoader.cs`, `Assets/Data/*.asset`, `Tools/Simulation/GameAssets.cs`(YAML 리더, 이후 `dev`에서 삭제됨).
- **산출물**: 에디터 도구, 콘텐츠 버전 계산 코드(C# 코어, W0-2가 재사용), 실제 콘텐츠로 내보낸 묶음.
- **완료 조건**: 내보낸 묶음이 A0-2 샘플과 같은 구조이고, 두 번 내보내면 버전이 같다.
- **선행**: A0-2.

#### W1-2. 동기화 엔드포인트와 적용 엔진 (서버, 2.5일) — 완료

- **목표**: `POST /api/v1/me/sync`. 잠금, seq 규칙(건너뜀·누락·멈춤), 처리기 호출, `sync_log` 기록, 항상 진행도를 응답한다.
- **참조**: 계획서 5.3, 6.1.
- **완료 조건**: 가짜 처리기로 작성한 단위·통합 테스트 통과(순차 적용, 재전송, 누락, N번째 거부 시 N−1까지 반영, 의심 표시 후 계속 진행, 빈 배열).
- **선행**: A0-4, A0-5.

#### W1-6. 결산 처리기와 `SettlementValidator` (서버, 2.5일) — 완료

- **목표**: 계획서 6.2의 S1~S9를 구현하고, 각 검사의 거부 코드와 허용 오차를 확정한다. S10(통계 검사)은 의심 표시만 남기는 형태로 넣을지 이 작업에서 결정한다.
- **참조**: 계획서 2.1(결산 값의 결정 구조), 6.2.
- **완료 조건**: 검사마다 통과·실패 단위 테스트. 이식 규칙은 B의 W1-4가 완료되기 전까지 가짜 구현으로 테스트한다.
- **선행**: A0-3, A0-5. 실제 규칙과의 통합은 W1-4(완료) 이후라 이제 할 수 있다.
- **결과**(A): `sync/handler/SettlementValidator`(순수 검사, 첫 위반 하나를 돌려준다)와 `SettlementHandler`(`LogHandler<SettlementPayload>` 빈)를 구현했다. 실제 `ContentBattleRules`와 샘플 묶음 위에서 `SettlementHandlerTests` 31개(검사마다 통과·실패, 경계값, 검사 순서, 거부 때 진행도 불변, 샘플 로그 두 개)가 통과한다. 전체 테스트(Testcontainers 포함)도 통과했다(2026-10-15).
- **결정·참고**:
  - **구매 시 열린 판 포기(계약 C31)**: 결산 검증은 결산 시점의 노드 랭크로 판 설정을 계산한다. 판 도중 `NODE_PURCHASE`가 끼면 더 강해진 노드 기준으로 검증이 느슨해지므로, 구매가 열린 판을 포기로 닫기로 했다(그 판의 결산은 `RUN_NOT_OPEN`). 처음에는 구매를 `RUN_IN_PROGRESS`로 거부하는 안이었으나, 정상 플레이어의 구매가 사라지고 서버 진행도로 덮어써지는 피해가 커서 바꿨다. 거부 코드 목록은 늘리지 않는다.
  - **S5 치명 보너스**: 치명이 0번이면 보너스는 0. c번이면 치명 확률이 0보다 큰 종류의 황금 처치 중 c개를 고른 보너스 합의 범위(가장 작은 c개 ~ 가장 큰 c개) 안이어야 한다.
  - **S7**: 수거형이 아니고 시간 추가 확률이 0보다 큰 종류의 처치 수 이하(계획서의 "수거형이 아닌 처치 수"보다 엄격).
  - **S8 허용 오차**: 상한 + 0.01초 + 상한의 0.01%(`SettlementValidator.TIME_TOLERANCE_*`). 클라이언트는 판 시간을 제한 시간 안으로 자르므로(`LimitStep`) 프레임 단위의 초과는 허용하지 않는다. 클라이언트 실측으로 보정하지는 않았다(아래 남은 일).
  - **S10(통계 검사)**: 이번에는 넣지 않았다. 기준을 정할 실측 데이터가 없다. 후속 과제.
  - **알려진 한계**: 클라이언트의 시간 추가 상한(`MaxKillTimeBonus`)은 서버 콘텐츠에 없어, 이 캡을 넘는 시간 추가는 막지 못한다(S7·S8은 상한만 본다). 합이 `long`을 넘는 장부는 `*_MISMATCH` 거부다.
  - **남은 일**: ① ~~`NodePurchaseHandler`가 모든 검사를 통과한 뒤 `context.abandonOpenRun()`을 부른다~~ — **반영 완료**(A, 테스트 포함: `NodePurchaseHandlerTests`, 결산과의 연결은 `SettlementHandlerTests`). ② ~~C의 W3-4 또는 클라이언트 테스트: 결산 로그가 구매 로그보다 먼저 만들어지는지(`ScreenFlow`의 결산 → 업그레이드 흐름) 확인하는 테스트를 하나 둔다. 구매가 먼저 오면 그 판의 보상이 알림 없이 사라진다.~~ — **반영**(W3-4, 클라이언트 `ScreenFlowLogOrderTests` 5개). `ScreenFlow`는 Unity 객체가 있어야 만들 수 있어 Editor 테스트가 직접 띄우지 않고, 로그를 남기는 세 지점을 같은 순서로 옮긴 흐름으로 확인한다. 그래서 `ScreenFlow`의 순서가 바뀌면 테스트도 함께 고쳐야 한다(테스트 주석에 근거 줄이 있다). ③ S8 허용 오차를 에디터 `Campaign Run`의 실제 `playedSeconds`와 한도의 차이로 확인(W4-2a·W4-2b 골든 로그에서 함께 확인할 수 있다).

#### W3-2. 클라이언트 로그 모델과 생성기 (클라이언트, 2.5일) — 완료

- **목표**: 코어(순수 C#)에 로그 모델, JSON 직렬화, 생성기를 만들고 계획서 7.3의 4개 지점에서 생성한다. `RUN_START`의 시작 적 수·제한 시간·시작 레벨과 `SETTLEMENT`의 값을 채운다.
- **참조**: 계획서 5.1~5.2, 7.3. A0-1 샘플.
- **완료 조건**: 생성한 JSON이 A0-1 샘플과 형식이 같다(W4-3 계약 테스트 통과). 칸별 집계는 C의 W3-1이 끝나기 전까지 A0-6 시그니처의 가짜 구현으로 채운다.
- **선행**: A0-1, A0-6.
- **결과**: `BlackHole.Sync.SyncLogFactory`(`Assets/Scripts/Sync/`)가 4종 로그를 모두 만든다(`NodePurchase`, `Reset`, `RunStart`, `Settlement`). `GameSession.StartInfo`를 연결했고(`GameSessionFactory`가 시드·성장도·시작 레벨·제한 시간·시작 공급을 채운다), `ScreenFlow`의 4지점(판 시작, 결산, 노드 구매, 새 게임)에서 로그를 남긴다. 판 시작의 `runId`는 `Guid.NewGuid()`로 만들어 결산까지 들고 간다. 로그 형식은 3.8 샘플과 글자까지 같다(`SyncLogFactoryTests` 5개, `SyncJsonTests`).
- **결정·참고**:
  - **포기**는 로그가 없다(계획서 D5: 다음 `RUN_START`나 `RESET`이 열린 판을 포기로 닫는다).
  - **판 시작 로그 없이 시작한 판**(테스트 도구)의 결산은 서버가 `RUN_NOT_OPEN`으로 거부하므로, 그 실행을 테스트 세션으로 보고 저장·로그를 모두 멈춘다(`EnterTestSession`).
  - **칸별 값**: `BattleRawData`의 `KillBuckets`·`GoldenCritCount`·`GoldenCritBonusGold`·`TimeBonusCount`는 W3-1이 센다(완료, PR #88). 그 전 `dev`에서는 결산 로그의 `kills`가 빈 배열이고 `goldenCritCount` 등이 0이어서 서버의 결산 검증을 통과하지 못했다. 이제 실제 값이 실린다.

#### W3-3. 저장 포맷 2 (클라이언트, 2.5일) — 완료

- **목표**: 진행도, 대기 로그, 다음 seq, 마지막 동기화 결과를 같은 원자적 쓰기로 저장한다. 포맷 1을 2로 옮긴다. 서버 진행도로 덮어쓰는 전용 경로를 만든다(`Capture`의 "큰 값 유지" 규칙을 우회).
- **참조**: 계획서 7.2, `Save/ProgressStore.cs`, `Save/ProgressSaveFile.cs`, `Core/Nodes/Save/*`.
- **완료 조건**: 포맷 1 파일 로드, 강제 종료 시나리오(쓰기 도중 실패), 덮어쓰기 경로 테스트.
- **선행**: A0-6.
- **결과**: `ProgressStore`(`Assets/Scripts/Save/`)가 `ISyncStore`를 구현한다. 로그 장부는 Unity 밖 순수 C#인 `SyncLedger`(`Assets/Scripts/Sync/`)가 가진다(다음 seq, 대기 로그, 마지막 거부, 5.7의 응답 반영 규칙). `Save`는 `BlackHole.Save` 어셈블리로 분리했다. 진행도와 장부는 `progress.json` 한 파일에 한 번의 원자적 쓰기(임시 파일 → 교체, 직전 저장 `.prev`)로 저장된다.
  - `Commit(makeLog)`·`StartNew(progress, makeLog)`: 로그 추가 + 진행도 저장을 한 번에 하고 `LogAppended`를 올린다. 테스트 세션이면 로그를 만들지 않는다.
  - `ApplyResponse`: 응답마다 파일에 쓴다. 새 게임·계속을 고르기 전(타이틀)에도 쓴다(대기 로그와 다음 seq를 잃지 않기 위해). 덮어쓰기는 `Capture`의 "큰 값 유지"를 거치지 않는다. 서버에 기록이 없고 로컬 저장도 없는 첫 실행이면 `Saved`를 만들지 않는다.
  - 포맷 1 파일은 `Sync`가 처음 상태로 읽히고 다음 저장 때 포맷 2가 된다. 알 수 없는 포맷 버전과 순서가 어긋난 `Sync`(seq)는 깨진 저장으로 보고 직전 저장으로 되돌아간다.
  - 쓰기 실패(`IOException`, `UnauthorizedAccessException`)는 오류 로그만 남기고 게임을 이어 간다. 장부는 메모리에 있어 다음 저장 때 다시 쓴다.
  - 테스트: `SyncLedgerTests` 12개, `ProgressStoreTests` 19개(포맷 1 로드, 쓰기 실패, 깨진 `Sync`, 거부·덮어쓰기, 타이틀 응답 등).

#### W4-2b. 골든·조작 로그 테스트 (서버, 1.5일)

- **목표**: C가 생성한 캠페인 로그(W4-2a)를 서버 테스트 픽스처로 넣는다. 골든 로그는 전부 통과하고, 조작 로그(골드 부풀리기, 경험치 불일치, 시작 없는 결산, 비용 위조, 누락 seq 등)는 기대한 seq·코드에서 멈추는지 확인한다. 조작 목록과 기대 코드를 A가 정한다.
- **선행**: W1-6, W4-2a.
- **참고**: 골든 로그의 범위는 W4-2a가 정한다(기본: 성장도 3까지). 범위 밖(마지막 이정표 이후)을 다루는 조작·정상 시나리오는 W1-6의 단위 테스트에 둔다.

---

### B — 서버 구현

B의 작업은 모두 서버 저장소(`Feed-A-Blackhole-Server`)에서 한다. 패키지는 `battle/content/`(전투 콘텐츠), `battle/rules/`(전투 규칙)와 `sync/handler/`(처리기)를 주로 쓴다. `sync/` 루트(엔진, 컨트롤러)는 A의 영역이다.

#### W0-3. 전투 콘텐츠 로드 (M, 2.5일, 첫 작업) — 완료

- **목표**: 계약 1.2~1.5의 묶음(`content/manifest.json`, `content/nodes/*`, `content/battle/battle.json`)을 시작 시 로드한다.
  - `battle.json`을 계약 1.5 객체(`BattleContent`)로 만들고 1.5의 로더 검증을 한다. 콘텐츠가 잘못되면 서버를 시작하지 않는다(기존 `NodeContentConfig` 방식).
  - 목차의 버전을 `ContentVersion`으로 다시 계산해 비교하고, 다르면 시작하지 않는다.
  - 노드 콘텐츠의 위치를 `content/nodes/`로 유지하되, `NodeTree.nodeContentVersion()`을 묶음의 `contentVersion`으로 대체한다.
  - 목차 자체도 검사한다: `formatVersion`이 1이고, 목록의 파일이 모두 있다.
  - 문제는 처음 하나에서 멈추지 말고 모두 모아 한 번에 알린다(기존 노드 로더와 같은 방식).
  - 읽은 결과를 Spring 빈으로 보관한다: `ContentManifest`(버전), `NodeTree`, `BattleContent`. 이후 코드(전투 규칙, 처리기)는 이 빈을 주입받아 쓰고, 요청 처리 중에는 파일을 읽지 않는다.
  - `src/main/resources/content/`를 샘플 묶음 내용으로 교체하고(노드 276개), 노드 수 검증 테스트(현재 247)를 갱신한다. W0-1의 실제 묶음이 나오면 다시 교체한다.
- **샘플 묶음이란**: `src/test/resources/content-sample/`은 클라이언트 내보내기 도구(W0-1, 아직 없음)가 만들 파일과 **같은 형식**이고, 값도 2026-10-12 클라이언트의 실제 값이다. 도구가 완성되면 같은 형식의 파일로 교체만 하면 된다.
- **참조**: 계약 1장, 기존 `node/content/*`, `NodeContentConfig`, `content/ContentVersion`(기준 구현), 샘플 묶음 `src/test/resources/content-sample/`. JSON은 서버에 있는 Jackson 3으로 읽는다.
- **완료 조건**: 샘플 묶음 로드 테스트, 잘못된 묶음(버전 불일치, 1.5의 검증 위반마다)이 시작을 막는 테스트. W0-1 이후 실제 묶음으로도 통과.
- **선행**: 없음(계약 1장 완료).

#### W1-4. 전투 규칙 이식 (L, 4.5일) — 완료

- **목표**: 계약 1.6의 `BattleRules`를 구현한다(계획서 4.3). 스탯 합산, 판 구성(크기 상한, 질량과 색 비율, 황금 배율·치명, 시간 추가 확률, 성질 확률, 시작 수 보너스, 변환 수), 칸별 골드·경험치, 시작 적 수, 제한 시간·성장 시간, Level 계산.
- **무엇을 만드는가**: 미리 계산해 들고 있는 데이터가 아니라, W0-3이 들고 있는 `NodeTree`·`BattleContent`로 **플레이어 진행도에 따른 판 설정을 그때그때 계산하는 서비스**다. 상태를 갖지 않는 빈으로 등록한다. 결산 검증(W1-6)과 판 시작 처리기(W1-5)가 이렇게 쓴다: 노드 랭크 → `upgradesFor` → `setupFor(성장 단계, …)` → `RunSetup`(시작 레벨, 제한 시간, 시작 적 수, 경험치→레벨) → `kind(적)` → `KindSetup`(최대 크기, 나올 수 있는 색, 처치 하나의 골드·경험치).
- **어떤 statId가 어디에 닿는지는 옮기지 않는다.** `BattleContent`의 `stats`(`GlobalStatIds`, `EnemyStatIds`)에 있다(계약 C3). 그 이름으로 `UpgradeValues`에서 값을 꺼낸다.
- **참조**: 계약 1.6~1.7(수치 일치 규칙), 클라이언트 코드
  - `Core/Enemies/Composition/EnemyComposition.cs`(`From`), `MassRule.cs`, `SizeRule.cs`
  - `Core/Enemies/Definitions/EnemyDefinition.cs`(`StatsAt`)
  - `Core/Battle/Session/GameSessionFactory.cs`(`StartSupplyOf`)
  - `Core/Battle/Time/TimeLimitDefinition.cs`, `Core/Hq/*`
  - `Core/Nodes/NodePurchase.cs`(`StatsFor`), `Core/Common/UpgradeStatValues.cs`
- **주의**: 계약 1.7의 수치 일치 규칙을 지킨다(반올림은 .5에서 0에서 먼 쪽, `float`·`double`과 연산 순서를 C#과 같게). 정확한 등식 검사가 1의 차이로도 실패한다.
- **완료 조건**: `BattleRules` 구현을 빈으로 등록, 샘플 묶음의 실제 콘텐츠로 규칙별 단위 테스트(예: 노드 없음·성장 단계 0이면 시작 레벨 0, 제한 시간 12초, 시작 적은 소행성 20마리). C# 기대값 표 일치는 W4-1b에서 확인한다.
- **선행**: W0-3(테스트에 실제 콘텐츠를 쓰기 위해).
- **결과**(B, PR #20 `feat/port-battle-rules`, 병합 `a171e1a`): `battle/rules/`에 `ContentBattleRules`(`BattleRules` 구현, `NodeTree`·`BattleContent`를 주입받는 상태 없는 `@Component`)를 두고, `upgradesFor`는 `NodeUpgradeValues`, `setupFor`는 `RunComposition`이 계산한다. 나머지는 `UpgradeChain`(스탯 합산), `MassRule`, `SizeRule`, `KindComposition`(`KindSetup`: 최대 크기, 나올 수 있는 색, 칸별 골드·경험치, 황금 치명 보너스), `StartSupply`(시작 공급: 보너스, 변환 사슬), `Rounding`(.5에서 0에서 먼 쪽 반올림)이다. 테스트는 샘플 묶음 기준(계약 C30)으로 약 50개(`battle/rules/*Tests`)다.
  - **남은 일**: C# 기대값 표와의 일치는 W4-1a가 표를 만든 뒤 W4-1b에서 확인한다. 이 작업으로 W1-5, W1-6의 실제 규칙 통합이 풀렸다.

#### W1-3. 노드 구매·새 게임 처리기 (M, 2.5일) — 완료

- **목표**: `NODE_PURCHASE`(`NodePurchase` 규칙 이식: 최대 랭크 → 노출 → 골드, `rank`·`cost` 일치)와 `RESET`(열린 판 포기, 진행도 초기화) 처리기.
- **참조**: 계획서 6.2, 클라이언트 `Core/Nodes/NodePurchase.cs`, `NodeGraph.cs`, 서버 `NodeTree`, `NodeGraph`.
- **완료 조건**: 처리기 단위 테스트(각 거부 사유 포함).
- **선행**: A0-1, A0-5.
- **결과**(B, PR #22 `feat/purchase-handler`): `sync/handler/`에 `NodePurchaseHandler`와 `ResetHandler`를 두었다. 둘 다 상태 없는 `@Component`이고 `LogHandlerRegistry`가 `type()`으로 찾는다.
  - `NodePurchaseHandler`(`NodeTree` 주입): 검사 순서는 계약 3.6 표와 같다. `UNKNOWN_NODE` → `MAX_RANK_REACHED` → `NODE_HIDDEN` → `NOT_ENOUGH_GOLD` → `RANK_MISMATCH` → `COST_MISMATCH`. 검사를 모두 마친 뒤에만 `spendGold`·`setNodeRank`를 부르며, 차감 금액은 payload의 `cost`가 아니라 서버 콘텐츠의 비용이다.
  - `ResetHandler`: 검증 없이 `resetProgress()`를 부르고 항상 적용한다(열린 판 포기와 노드 삭제는 `JpaSyncContext`가 한다).
  - 테스트: `NodePurchaseHandlerTests` 16개(각 거부 사유와 검사 순서, 정상 구매, 2랭크 이후 구매), `ResetHandlerTests` 4개는 샘플 묶음과 `FakeSyncContext`로 DB 없이 돈다. `NodePurchaseSyncTests` 6개는 실제 처리기·엔진·DB로 구매 반영, 거부 후 멈춤, 산 순서 보존, 새 게임을 확인한다.
  - **남은 일**: 없음. W1-7(옛 API 정리)과 W1-8(E2E)은 이 처리기를 쓸 수 있다.

#### W1-5. 판 시작 처리기 (S, 1일) — 완료

- **목표**: `RUN_START` 처리기. 열린 판 포기, `runId` 중복 거부, 서버 계산값과 비교해 불일치하면 의심 표시(멈추지 않음).
- **완료 조건**: 단위 테스트(정상, 각 값 불일치, 열린 판 포기, 중복 `runId`).
- **선행**: A0-5, W1-4.
- **결과**(B, `feat/run-start`): `sync/handler/RunStartHandler`를 두었다. 상태 없는 `@Component`이고 `BattleRules`를 주입받는다.
  - 검사 순서: `runIdUsed`로 중복을 먼저 보고(`DUPLICATE_RUN_ID`, 거부할 때는 열린 판을 닫지 않는다) → 서버 진행도로 `upgradesFor`·`setupFor`를 계산해 `startGrowthStage`, `startGold`, `startLevel`, `timeLimitSeconds`(0.001초 허용), `startSupply`(순서까지)를 비교 → 열린 판을 포기로 닫고 `openRun(payload, 의심 사유)`를 부른다. 사유는 `SuspicionReason` 선언 순서로 쌓이고, 있어도 거부하지 않는다(D8).
  - 판의 시작 Gold·성장도는 서버 진행도 값으로 저장되고(C20), 의심 사유가 있으면 계정의 `suspicious_count`가 판 하나당 1 오른다.
  - 테스트: `RunStartHandlerTests` 15개(샘플 묶음과 `FakeSyncContext`로 DB 없이), `RunStartSyncTests` 7개(실제 처리기·엔진·DB: 판 저장, 의심 사유 저장, 의심 후 계속 진행, 열린 판 포기, 중복 `runId`, 서버 진행도 기준 계산).
  - **남은 일**: 없음. W1-8은 이 처리기를 쓸 수 있다.

#### W1-7. 옛 API 정리 (S, 1일) — 완료

- **목표**: `PUT /me/progress`, `POST /me/progress/reset` 제거, `GET /me/progress`에 `lastAppliedSeq` 추가, 관련 테스트 정리.
- **선행**: W1-2.
- **결과**(B, `feat/remove-old-progress-api`; V4는 A):
  - 제거: `ProgressController`의 PUT·reset, `SaveProgressRequest`, `ProgressService.save`·`reset`, `ErrorCode`의 `STALE_PROGRESS`·`PROGRESS_REGRESSION`, `PlayerNodeRankRepository.deleteAllByAccountId`(새 게임은 `JpaSyncContext`가 행을 하나씩 지운다).
  - `ProgressResponse`는 `revision` 대신 `lastAppliedSeq`를 낸다(동기화 응답의 `progress`와 같은 형식).
  - `V4__drop_progress_revision.sql`로 `player_progress.revision` 열을 지웠다(계약 C21). `PlayerProgress`에서 `revision` 필드와, 호출처가 없어진 `update`·`reset`을 지웠다.
  - 테스트: `ProgressApiTests`는 조회 중심으로 바꾸고 옛 API 부재(405/404)와 동기화 응답과의 일치를 본다. `ProgressSchemaTests`는 `revision` 열이 없음을 확인한다. `FakeClient`에서 `saveProgress*`·`resetProgress`·`revision()`을 지웠고, `Stage1ClientFlowTests`에서 옛 저장 전용 E2E를 지웠다.
  - **W1-8 참고**: 동시 요청 테스트의 기준으로 적었던 `ofTwoConcurrentSavesWithTheSameBaseRevisionExactlyOneWins`는 지워졌다. 동시 요청 도우미(`runTogether`)와 `startedClient()`는 `Stage1ClientFlowTests`에 그대로 있고, 옛 테스트는 git 이력(`01e9797`, W1-7 이전 `dev`)에서 볼 수 있다.
  - **남은 일**: 없음.

#### W4-1b. 이식 일치 테스트 (서버) (S, 1일)

- **목표**: A가 생성한 기대값 표(W4-1a)를 읽어 W1-4 구현이 같은 값을 내는지 확인하는 테스트.
- **참조**: 계약 1.7a(파일 형식과 이 테스트가 지킬 것), 1.7(수치 일치 규칙), `RealContentRulesTests`(실제 묶음을 읽는 기존 방식).
- **산출물**: `src/test/java/.../battle/rules/RulesTableTests.java` (이름은 자유). 표 파일은 `src/test/resources/fixtures/rules-expected.json`이며 W4-1a가 만들어 서버에 커밋한다.
- **완료 조건**: 현재 실제 콘텐츠에서 모든 stage·loadout의 모든 값이 완전히 일치한다. 파일의 `contentVersion`이 서버 목차와 다르면 "픽스처 재생성 필요"로 실패한다. 어긋나면 어느 loadout의 어느 필드인지 메시지에 나온다. 표 파일이 아직 없을 때는 계약 1.7a의 예시 구조로 만든 작은 파일로 읽기·비교 코드를 먼저 짠다(파일은 W4-1a가 실제 값으로 바꾼다).
- **선행**: W1-4, W4-1a(형식은 계약 1.7a로 확정되어 있어 코드는 먼저 시작할 수 있다).

#### W1-8. E2E와 CI (M, 2.5일)

- **목표**: `FakeClient`로 실제 HTTP 흐름 E2E(등록 → 로그인 → 동기화: 순차 적용, 재전송, 누락, 거부 후 멈춤, 의심 표시 후 계속, 동시 요청)를 만들고, 이 테스트를 돌리는 **CI 워크플로를 코드 스타일 검사와 별개로** 추가한다(Testcontainers).
- **선행**: W1-2, W1-3, W1-5, W1-6. W1-5(완료)가 끝난 뒤에 시작한다(`RUN_START` 처리기가 없으면 기본 샘플 시나리오가 `500`이다). W1-7과는 순서가 없다(아래 "W1-7과의 경계").
- **일정 배분(2.5일)**: ① `FakeClient`·지원 클래스 0.5일 → ② 시나리오 1.5일 → ③ CI 0.5일. 순서대로 하되, ③은 ①이 끝나면 따로 PR을 내도 된다.
- **이 작업의 범위가 아닌 것**: 엔진 한도(500개·1 MB의 413), 로그 형식 검사(`MALFORMED_LOG`), 처리기별 거부 사유 전부는 `SyncApiTests`와 처리기 단위 테스트가 이미 본다. E2E는 **실제 처리기·실제 HTTP·실제 커밋이 한 줄로 이어질 때만 생기는 문제**(적용 순서, 이중 적용, 잠금, 인증 재시도)를 본다.

**① `FakeClient`와 테스트 지원 (`src/test/java/.../support/`)**

- `FakeClient`에 동기화 메서드를 **추가**한다. 요청은 서버 DTO가 아니라 JSON 문자열로 조립한다(기존 원칙).
  - `Response sync(String requestJson)`: `call("POST", "/me/sync", requestJson)`. `call`을 거치므로 401이면 갱신 후 한 번 재시도하는 흐름이 그대로 적용된다.
  - `Response sync(String... logJsons)`: 로그 객체 글자들을 `{"logs":[...]}`로 이어 붙여 보낸다. **로그 글자는 바꾸지 않고 이어 붙이기만 한다**(계약 3.1: 재전송해도 같은 글자).
  - 응답 편의: 거부 여부는 `rejected`가 `null`인지로 본다(`response.read("$.rejected")`). `appliedThroughSeq`, `rejected.code`, `progress.*`는 기존 `Response`의 JsonPath로 읽는다.
  - 옛 진행 API용 `saveProgress`, `saveProgressBasedOn`, `resetProgress`, `revision`은 W1-7에서 이미 지웠다(아래 참고).
- `SyncSamples`(새 지원 클래스): `src/test/resources/sync-samples/`의 파일을 읽는 도우미.
  - `request()`는 `sync-request.json`의 로그 6개를 **원본 글자 그대로** 잘라서 돌려준다(예: `logs(1, 3)` = seq 1~3). 시나리오마다 샘플을 복사해 값을 손으로 고치지 않는다.
  - 잘라 내기는 JSON을 읽어 `logs[i]`를 글자로 되돌리면 된다(Jackson 3, `tools.jackson`). 이 도우미는 테스트 코드이므로 `FakeClient`와 달리 Jackson을 써도 된다.
  - 값을 일부러 바꾼 로그(의심 표시용 `RUN_START`, 거짓 `contentVersion` 등)는 `logs(1, 1)` 결과에서 필요한 필드 하나만 바꿔 만든다.
- 새 테스트 클래스 `e2e/SyncClientFlowTests`. `Stage1ClientFlowTests`와 같이 `@SpringBootTest(RANDOM_PORT)` + `TestcontainersConfiguration`을 쓰고 테스트 전체를 트랜잭션으로 묶지 않는다(실제 커밋과 동시 요청을 보려는 것). `@Import`의 구성을 `Stage1ClientFlowTests`와 **같게** 두면 Spring이 컨텍스트와 MySQL 컨테이너를 재사용해 시간이 줄어든다.
- 테스트마다 **새 계정**(`new FakeClient(...)` → `start()`)을 쓴다. 샘플의 `runId`는 계정 안에서만 유일하면 되므로(`uk_battle_run_account_run`) 계정이 다르면 같은 샘플을 여러 테스트가 써도 부딪히지 않고, 뒷정리가 필요 없다.
- DB 확인이 필요한 곳(`sync_log`의 결과, `battle_run`의 의심 사유)은 HTTP로는 볼 수 없으므로(조회 API가 없다, 계약 4.4) `SyncLogRecordRepository`, `BattleRunRepository`를 주입해 읽는다. 필요한 조회 메서드는 테스트 쪽에서 추가한다.

**② 시나리오 (`SyncClientFlowTests`)**

기본 데이터는 샘플 묶음(`contentVersion` `4d6c398ad1f5e49c`)의 `sync-request.json`(seq 1 판 시작 → 2 결산 → 3 `timer-01` 구매 → 4 판 시작 → 5 결산 → 6 새 게임)이다. 기대값은 `sync-response-*.json`과 계약 3.4·3.6·3.7에서 가져온다.

| # | 시나리오 | 보내는 것 | 확인할 것 |
|---|---|---|---|
| 1 | 순차 적용 | 새 계정에 seq 1~6 한 번에 | `appliedThroughSeq` 6, `rejected` null, 진행도가 `sync-response-applied.json`과 같다(`updatedAt` 제외: Gold 0, 노드 없음, `lastAppliedSeq` 6). `sync_log`가 6건 모두 `APPLIED` |
| 1b | 나눠 보내기 | seq 1~3 → 이어서 4~6 (요청 둘) | 1~3 뒤 진행도: Gold 18, `timer-01` Rank 1(결산 20 − 구매 2, seq 4의 `startGold` 18과 같다). 4~6 뒤는 1과 같다 |
| 2 | 재전송 | seq 1~3을 두 번, 그다음 seq 2~5(겹쳐서) | 두 번째 요청도 `appliedThroughSeq` 3, 거부 없음, Gold·Rank가 두 배로 늘지 않는다(Gold 18, Rank 1). 겹친 요청은 2·3을 건너뛰고 4·5만 반영한다(`sync_log`에 `SKIPPED` 2건) |
| 3 | 누락 | 새 계정에 seq 3부터(`lastAppliedSeq` 0) | `rejected.seq` 3, `SEQ_GAP`, `appliedThroughSeq` 0, 뒤의 로그는 `NOT_PROCESSED`. 이어서 seq 1~6을 보내면 정상 반영된다. 요청 **안에서** seq가 비는 경우(1, 3)는 거부가 아니라 `400 INVALID_REQUEST`이고 아무것도 반영되지 않는다(계약 3.4) |
| 4 | N번째 거부 | `sync-request-tampered.json`(seq 2 결산의 Gold를 99999로) | `appliedThroughSeq` 1, `rejected` = seq 2 `SETTLEMENT_GOLD_MISMATCH`, 진행도는 `sync-response-rejected.json`과 같다(`lastAppliedSeq` 1). seq 3은 `NOT_PROCESSED`. 이어서 **고친** seq 2부터 다시 보내면 반영된다(거부된 seq의 재사용) |
| 5 | `RUN_START` 불일치 후 계속 | seq 1의 `startGold`를 999로 바꾼 `RUN_START`, 이어서 샘플의 seq 2(결산)·3 | 거부 없이 모두 반영. `battle_run.suspicious_reasons`에 `START_GOLD_MISMATCH`, `player_progress.suspicious_count` 1. 이후 결산·구매가 서버 값 기준으로 정상 처리된다(C20). `timeLimitSeconds`만 0.001 이내로 바꾼 경우는 의심이 남지 않는다(계약 4.4) |
| 5b | 중복 `runId` | 같은 `runId`의 `RUN_START` 두 번(첫 판을 결산하지 않고) | 두 번째가 `DUPLICATE_RUN_ID`로 거부되고 첫 판은 그대로 열려 있다 |
| 6 | 동시 요청: 같은 로그 | 같은 계정을 쓰는 두 기기(`anotherDevice()`)가 seq 1~3을 동시에 | 둘 다 `200`, `rejected` null, 최종 `lastAppliedSeq` 3, Gold 18, `timer-01` Rank 1(이중 적용이면 결산 Gold가 두 번 들어가거나 구매 로그가 중복 처리되어 값이 어긋난다). `sync_log`에서 `APPLIED`는 seq마다 정확히 1건. 라운드마다 새 계정으로 15회 반복 |
| 6b | 동시 요청: 겹치는 범위 | 한 기기는 seq 1~3, 다른 기기는 seq 1~5를 동시에 | 어느 쪽이 먼저든 최종 `lastAppliedSeq` 5, 이중 적용 없음 |
| 7 | 인증과 동기화 | 액세스 토큰을 만료시킨(`MutableClock`) 뒤 동기화 | `401 UNAUTHORIZED` → 갱신 → 같은 요청 재시도로 **한 번만** 반영된다. 토큰 없는 요청은 `401`(공통 오류 형식) |
| 8 | 콘텐츠 버전 | 한 로그의 `contentVersion`만 다르게 | `CONTENT_VERSION_MISMATCH`로 거부, 앞의 로그는 반영 |
| 9 | 빈 요청 | `sync-request-empty.json` | 진행도만 돌아온다(`appliedThroughSeq` = `lastAppliedSeq`, 거부 없음) |

- 동시 요청 도우미(`runTogether`)와 `startedClient()`는 `Stage1ClientFlowTests`의 것을 참고해 새 클래스에 만든다(공용으로 뽑지 않는다).
- 동시 요청 테스트는 한 번 통과로 끝내지 않는다. 라운드를 반복하고(15회) 실패하면 라운드 번호가 나오게 `as("라운드 %d", round)`를 붙인다. 기준 구현은 `Stage1ClientFlowTests.ofTwoConcurrentRefreshesWithTheSameTokenExactlyOneSucceeds`다(옛 저장 동시성 테스트는 W1-7에서 지웠다).
- 규칙 값(Gold·Rank)은 샘플에서 정한 숫자를 쓴다. 표의 숫자가 구현과 다르면 테스트를 맞추지 말고 **샘플·처리기 중 어느 쪽이 틀렸는지 먼저 확인**한다(샘플은 계약 3.8의 기준이다).

**W1-7과의 경계**

- W1-8은 `FakeClient`에 메서드를 **더하기만** 하고 `Stage1ClientFlowTests`는 건드리지 않는다. 옛 API(`PUT /me/progress`, `POST /me/progress/reset`)와 `revision`을 쓰는 코드와 테스트를 지우는 일은 W1-7이 한다(계약 C21).
- 둘 중 **나중에 끝나는 쪽**이 남은 정리를 한다: W1-7이 먼저면 W1-8의 `FakeClient` 수정 때 옛 메서드가 이미 없고, W1-8이 먼저면 W1-7이 `FakeClient`의 옛 메서드와 `revision` 필드를 지운다.
- `api-spec.md`의 "기준 구현은 `FakeClient`이며 … 고친다(W1-8)" 문장은 이 작업을 마칠 때 현재형("동기화 흐름까지 구현한다")으로 고친다.

**③ CI 워크플로 (코드 스타일과 분리)**

- 새 파일 `.github/workflows/test.yml`을 만들고, 기존 `code-style.yml`은 **고치지 않는다.** 이유: 검사 목적과 실행 시간(Checkstyle은 컴파일 없이 문법만 보고, DB 테스트는 컨테이너를 띄운다)이 달라 한쪽이 실패해도 다른 쪽의 결과가 보여야 하고, 브랜치 보호의 필수 검사도 따로 지정할 수 있다.
- 구성:
  - `name: Tests`, 잡 이름 `Test`. 트리거는 `code-style.yml`과 같게 `pull_request`, `push`(`main`), `workflow_dispatch`.
  - `permissions: contents: read`, `timeout-minutes: 20`. 같은 PR에 새 푸시가 오면 이전 실행을 취소하도록 `concurrency`(`group: tests-${{ github.ref }}`, `cancel-in-progress: true`)를 둔다.
  - `ubuntu-latest`(Docker가 기본으로 있어 Testcontainers가 그대로 돈다), `actions/checkout@v4`, `actions/setup-java@v4`(temurin 21, `cache: gradle`).
  - 실행: `bash ./gradlew --no-daemon test`. **`.env`나 시크릿이 필요 없다**: `build.gradle`이 테스트용 `JWT_SECRET`과 샘플 콘텐츠 위치를 이미 지정한다.
  - 실패했을 때 `build/reports/tests/`와 `build/test-results/`를 `actions/upload-artifact@v4`로 올린다(`if: failure()`).
  - MySQL은 서비스 컨테이너로 따로 띄우지 않는다. 테스트가 `TestcontainersConfiguration`으로 `mysql:8.4`를 직접 띄운다.
- 확인 순서: 로컬에서 `./gradlew test`가 통과하는 것을 먼저 확인 → PR에서 워크플로 실행 확인 → 일부러 실패하는 테스트를 넣었을 때 잡이 실패하는지(그리고 리포트가 올라오는지) 한 번 확인 후 되돌린다.
- 동시 요청 테스트가 CI의 느린 러너에서 흔들리면(가끔 실패) 라운드 수를 줄이는 것이 아니라 **실패한 라운드의 원인을 먼저 본다.** 흔들림은 대개 잠금이나 이중 적용 버그의 신호다.
- 문서 갱신: `README.md`의 "전체 빌드·DB 테스트를 실행하는 GitHub Actions CI는 후속 단계입니다…" 문장과 `CONTRIBUTING.md`의 CI 설명(13~14행)에 Tests 워크플로를 반영한다. 병합 차단에 쓰려면 GitHub의 `main` 보호 규칙에서 **Test** 검사를 필수로 지정해야 하며, 이것은 저장소 변경만으로는 되지 않는다(팀이 직접 설정).
- **완료 조건(CI)**: PR에서 `Checkstyle`과 `Test`가 각각 별개의 검사로 보이고, `Test`가 테스트 전체(이 E2E 포함)를 실제로 실행해 통과한다.

**완료 조건(전체)**

- 위 시나리오 1~9가 로컬과 CI에서 통과한다(동시 요청은 반복 라운드 포함).
- `FakeClient`가 `/me/sync`를 보낼 수 있고, 새 `SyncSamples`를 통해 샘플 파일을 원본 그대로 쓴다.
- `Tests` 워크플로가 `Checkstyle`과 별개로 동작하고 README·CONTRIBUTING이 이를 설명한다.

#### W5-2. API 명세 갱신 (S, 1일)

- **목표**: 서버 API 명세에 `/me/sync`, 거부 코드, 제거된 API를 반영한다.

---

### C — 클라이언트 구현

C의 작업은 모두 클라이언트 저장소(`Feed-A-Blackhole-Client`)에서 한다. `Assets/Scripts/Net/`(인터페이스와 타입은 A가 만들어 두었다, 계약 2.10)의 구현과, `UI/Flow`, `Battle/GameBootstrap.cs`의 연결 코드를 주로 다룬다.

#### W2-1. 네트워크 계층 (M, 2.5일, 0단계부터 시작)

- **목표**: 계약 2.4의 전송 인터페이스를 구현한다.
  - `IHttpTransport`의 `UnityWebRequest` 구현(`BlackHole.Net.Unity`). 예외를 던지지 않고 결과를 셋(서버가 답함 / 네트워크 오류 / 타임아웃) 중 하나로 돌려준다. 서버 오류 본문(`{"error":{"code","message"}}`)은 파싱해 `HttpResult.Error`에 넣는다. `BearerToken`이 있으면 `Authorization` 헤더, 본문이 있으면 `Content-Type: application/json`을 붙인다.
  - `IJsonSerializer`의 `JsonUtility` 구현(`BlackHole.Net.Unity`).
  - 테스트용 가짜 전송(정해 둔 응답을 돌려주고 받은 요청을 기록한다, `BlackHole.Net.Tests`).
- **참조**: 계약 2.1~2.4, `Analytics/Transport/AnalyticsClient.cs`(같은 방식의 기존 구현), API 명세 "오류 응답".
- **완료 조건**: 성공, 서버 오류(코드 파싱 포함), 5xx, 타임아웃, 연결 실패를 확인하는 테스트. 로컬 서버에 실제 요청 하나(예: 등록)가 가는 것을 수동으로 확인.

#### W2-2. 게스트 인증 (M, 2.5일)

- **목표**: 계약 2.5~2.7을 구현한다.
  - `ICredentialStore`의 파일 구현: 진행 저장과 같은 폴더의 `account.json`, 임시 파일에 쓴 뒤 바꿔치기, 깨진 파일은 백업으로 옮기고 "없음"으로 본다.
  - `IAuthSession` 구현: 앱 시작 시 한 번, 로그인 시간 예산(8초) 안에서 refresh 갱신 → 아이디/시크릿 로그인 → (계정 정보가 없으면) 등록 후 로그인.
  - 저장 순서: 등록에 성공하면 **로그인 전에** 아이디·시크릿을 저장하고, 새 refresh 토큰은 **다른 일을 하기 전에** 저장한다.
  - 서버에 닿지 않으면(네트워크 오류, 타임아웃, 5xx) 이번 실행은 오프라인이고 계정 정보는 건드리지 않는다.
  - 로그인이 `INVALID_CREDENTIALS`면 **새 계정을 만들지 않고** 오프라인으로 둔다(계약 2.7).
  - 실행 중 401 처리(`RefreshAfterUnauthorizedAsync`): 갱신 한 번, 안 되면 로그인 한 번. 갱신 요청은 동시에 하나만 나간다.
  - 개발 빌드 테스트 도구에 "계정·진행 초기화" 버튼.
- **참조**: 계약 2.4~2.7(흐름과 응답별 처리 표), API 명세 "인증", 서버 `support/FakeClient.java`(같은 흐름의 기준 구현).
- **완료 조건**: 가짜 전송으로 계약 2.6 표의 경우마다 테스트. 로컬 서버를 상대로 등록 → 로그인 → 앱 재시작 후 갱신이 된다. 저장 전 종료를 흉내 낸 경우(재사용 탐지 → 아이디/시크릿으로 복구).
- **선행**: W2-1, 계약 2장(계정 정보 파일 형식, 인증 인터페이스).

#### W2-3. 연결 상태와 타이틀 (S, 1일) — 완료(A가 진행)

- **목표**: 계약 2.3, 2.8을 구현한다.
  - `ServerSettings` 에셋(`Assets/Data/ServerSettings.asset`)을 만들어 `GameBootstrap`에 연결하고, 앱 시작 시 `IAuthSession.StartAsync()`를 부른다.
  - 설정이 꺼져 있거나 밸런스 프로필을 적용한 실행은 로그인하지 않고 `Offline(Disabled)`.
  - 타이틀에 연결 상태를 작은 텍스트로 표시한다(문구는 계약 2.8 표).
  - 로그인이 끝날 때까지 시작 버튼을 막는다. 시작 동기화(W3-4)가 생기면 동기화까지 기다리도록 확장한다(W3-4에서 반영).
  - 이번 실행 중에는 재연결하지 않는다(계획서 D9).
- **참조**: 계약 2.3, 2.8, `UI/Flow/ScreenFlow.Title.cs`, `Battle/GameBootstrap.cs`, `Battle/Analytics/AnalyticsSettings.cs`(설정 에셋의 기존 예).
- **완료 조건**: 서버를 켠 상태와 끈 상태에서 각각 타이틀 표시와 시작 버튼 동작을 확인.
- **선행**: W2-2.
- **구현(2026-10-14, A)**: 별도 타이틀 화면 작업은 범위 밖(클라이언트 폴리싱 담당)으로 두고, 기존의 콘텐츠 로딩 시점에 로그인을 더했다.
  - `GameBootstrap`이 콘텐츠·진행 저장을 읽은 뒤 **타이틀로 가기 전에** `IAuthSession.StartAsync()`를 기다린다(최대 `loginBudgetSeconds`, 대기 중 화면 표시 없음). `ServerSettings.asset`을 만들어 연결했다.
  - 설정이 없거나 꺼져 있거나 밸런스 프로필을 적용한 실행은 `OfflineAuthSession`으로 `Offline(Disabled)`.
  - **하지 않은 것**: 타이틀의 연결 상태 텍스트, 시작 버튼 막기(계약 2.8). 로그인이 타이틀 진입 전에 끝나므로 버튼 막기는 필요 없고, 상태 텍스트는 클라이언트 폴리싱에서 정한다. 계약 2.8에 같은 메모를 남겼다.
  - 확인: 에디터에서 서버 꺼짐(`Unreachable`), 켜짐 첫 실행(등록 후 로그인)·재실행(refresh 갱신), 설정 미연결(`Disabled`). 타이틀의 실제 화면 표시와 서버 무응답 시 8초 대기는 확인하지 못했다.

#### W3-0. 판 실행기 복원 (S, 0.5일) — 완료(A가 진행)

- **배경**: 클라이언트 `dev`가 시뮬레이션 도구를 삭제했다(2026-10-14). 도구 전체(에셋 YAML을 읽는 net8.0 CLI, 479줄의 탐욕 봇)는 되살리지 않았다. 에디터에서는 `GameContentLoader.Load(setup)`(공개)로 콘텐츠를 에셋에서 바로 읽으므로 YAML 리더가 필요 없다. 판을 헤드리스로 돌리는 데 필요한 최소 부분만 에디터 도구로 새로 두었다. W3-1의 검증, W4-1a, W4-2a가 쓴다.
- **구현**: `Assets/Scripts/Editor/SyncFixtures/`(에디터 전용, 빌드에 들어가지 않는다, 별도 asmdef 없음). 새 파일만 추가했고 기존 파일은 바꾸지 않았다. 네임스페이스는 `BlackHole.EditorTools.SyncFixtures`다(삭제된 `BlackHole.Simulation` 이름은 쓰지 않는다).
  - `HeadlessBattle`: 판 하나를 봇으로 끝까지 돌리고 결산한다. 삭제 전 `BattleRunner`(`git show 97ccf73:Assets/Scripts/Simulation/BattleRunner.cs`)에서 필요한 부분만 옮겼다. 판 만들기 → 시작 → 프레임마다 [봇 조준 → 진행] → 종료 → 남은 적 치우기 → 원시 기록 → 결산. 결과(`HeadlessBattleResult`)에 `RunStartInfo`, 시작·결산 후 Gold, `BattleRawData`, 중단 여부가 있어 `SyncLogFactory`가 그대로 쓴다.
  - `NearestEnemyBot`: 지금 조준점에서 가장 가까운 살아 있는 적을 겨냥하는 최소 봇이다. **블랙홀 둘레를 도는 `CircleAimBot`은 쓰지 않는다.** 시작 콘텐츠의 공격(피해 1, 간격 1초, 반경 0.87)으로는 처치 0, Gold 0에서 진행이 멈춘다.
  - `CheapestFirstBuyer`: 싼 노드부터 사며 구매 기록(노드, Rank, 비용)을 돌려준다. `ScenarioRunner`의 자동 구매는 산 개수만 알려 주어 로그에 쓸 수 없어서 따로 두었다(같은 규칙: 싼 것부터, 같은 값이면 노드 id 순).
  - `SyncFixturesMenu`, `SyncFixturesWindow`: 스모크 실행 메뉴와 창(아래 "실행 방법"). 재현성 확인, 이어서 [판 → 결산 → 구매] 진행, 소요 시간 측정. 이정표에 닿지 못한 채 N판이면 막힘으로 멈춘다.
- **실행 방법**(Unity 에디터, 메뉴 `BlackHole > Sync Fixtures`):

  | 메뉴 | 동작 | 용도 |
  |---|---|---|
  | `Smoke Run` | 30판을 바로 돌린다(약 0.3초). 결과는 콘솔에 남는다 | 빠른 확인, 재현성 |
  | `Campaign Run (성장도 3까지)` | 이정표 없이 300판이면 막힘으로 멈춘다. 지금 봇으로는 약 600판, 1분 안팎. 백그라운드로 돌고 취소할 수 있는 진행 막대가 뜬다. 끝나면 보고서가 콘솔에 남는다 | 이정표 도달과 막힘까지 |
  | `Run Window...` | 창에서 판 수, 판 상한(초), 막힘 기준(0이면 끔), 출력 간격(0이면 판 수에 맞춰 자동)을 정해 돌린다. 실행·취소 버튼, 끝난 뒤 보고서와 복사 버튼이 있고 값은 에디터를 다시 열어도 남는다 | 판 수를 조절해야 할 때 |

  - **결과 보는 법**: 콘솔(`Window > General > Console`)의 `[SyncFixtures]` 로그(창은 창 안에 표시). `재현성 ... 같다`여야 하고, `상한에서 끊긴 판 0개`여야 한다. `판 N:` 줄에 처치, 번 Gold, Level, EXP, 이정표 도달 여부, 구매 수, 소요 시간이 있다. 콘텐츠가 바뀌면 `콘텐츠 버전`과 수치가 달라진다.
  - **코드 호출**: 다른 도구(W4-1a, W4-2a)나 자동 실행은 `SyncFixturesMenu.SmokeRun(판 수, 판 상한, 출력 간격, 막힘 기준)`(동기) 또는 `StartBackgroundSmoke(...)`(백그라운드, 결과는 `BackgroundReport`, 진행은 `Progress`, 취소는 `CancelRequested`)를 부른다.
- **참조**: 삭제 전 커밋 `97ccf73`의 `Assets/Scripts/Simulation/`, 계약 5.3.
- **결정(확정)**: 에디터 도구는 테스트 어셈블리가 참조할 수 없다(`GameContentSetup`·`GameContentLoader`가 `Assembly-CSharp`에 있다). 그래서 판 실행기와 봇은 에디터 전용으로 두고, NUnit 테스트 대신 위 스모크 실행과 아래 측정으로 확인했다. 판 실행기를 Unity 무관 어셈블리에 두는 안은 테스트용 `GameContent`를 만들 방법이 필요해 택하지 않았다.
- **완료 조건과 확인 결과**(2026-10-14, 현재 콘텐츠 `bbdb35fcdf5bc984`):
  - 에디터에서 실제 콘텐츠로 판을 끝까지 돌려 결산까지 마친다: 충족. 500판 이상, Gold와 성장도가 진행도에 반영된다.
  - 같은 시드·같은 진행도면 결과가 같다: 충족(재현성 검사 항상 통과).
  - 안전 상한에서 끊기면 표시가 남는다: 충족(`Capped`). 상한에서 끊긴 판은 0개였다.
  - 결과에서 `BattleRawData`와 `RunStartInfo`를 읽을 수 있다: 충족.
  - 기존 파일 변경 없음, 빌드에 포함되지 않음: 충족.
  - 판당 소요 시간(에디터 Mono): 초반 2~11ms, 후반 200~600ms, 500판에 56초(평균 113ms). 메뉴 `Campaign Run`으로는 600판에 74초(평균 123ms), 성장도 3에서 막혀 멈췄다.
- **알려진 한계**: `NearestEnemyBot`은 성장도 3(Lv30)에 약 300판에 닿지만, 이후 모든 노드를 사고도 Lv38에서 멈춰 마지막 이정표(Lv40)에 닿지 못한다. 자세한 영향은 W4-2a.
- **선행**: 없음.

#### W3-1. 칸별 처치 집계 (M, 2.5일) — 완료

- **목표**: 코어 처치 집계를 (종류, 색 등급, 크기, 황금 여부) 칸별로 확장한다. 황금 치명 횟수·보너스 골드, 시간 추가 성공 수를 따로 센다. `BattleRawData`에서 읽을 수 있게 한다.
- **참조**: `Core/Enemies/EnemyRoster.cs`, `DeathRewards.cs`, `Core/Battle/World/DeathBonuses.cs`, `World.cs`, `Session/BattleRawData.cs`. 시그니처는 계약 5장.
- **완료 조건**: W3-0의 판 실행기(`HeadlessBattle.Run`)로 돌린 판에서 Σ(칸 처치 수 × 칸 경험치) + 시작 경험치 == `Hq.Exp`, Σ(칸 처치 수 × 칸 골드) + 치명 보너스 == `EarnedGold`인지 확인한다. 판 실행기는 에디터 전용이라 NUnit 테스트가 참조할 수 없으므로, `Editor/SyncFixtures`의 스모크 실행에 이 검사를 추가하는 방식으로 한다(결과의 `Raw`에서 칸별 장부를 읽는다).
- **선행**: 계약 5장의 `KillBucket` 시그니처, W3-0.
- **결과**(클라이언트 PR #88 `feat/BattleResultRequest-clean`, 병합 `10cfa25`):
  - `EnemyRoster`가 사망 확정 순간(`RecordDeath`)에 칸별 처치 수(처음 처치한 순서)와 황금 치명 횟수·보너스 Gold를 센다. 보너스는 `KillGold`와 같은 보상 값(`DeathReward`)에서 읽어 `EarnedGold`와 어긋날 수 없다. 칸의 황금 여부와 황금 치명 판정은 같은 함수(`DeathRewards.TryGetGolden`)를 쓴다.
  - `DeathBonuses.Roll`이 시간 추가 성공 수를 센다(수거형, 쿨타임으로 건너뛴 판정은 세지 않는다).
  - `World` → `GameSession.CreateRawData` → `BattleRawData`(생성자 인자, 모두 읽기 전용)로 넘긴다. `SyncLogFactory`가 이미 이 속성을 읽으므로 실제 게임의 결산 로그에 바로 실린다.
  - 판이 끝난 뒤 치운 적(`ClearRemainingEnemies`)은 세지 않는다(기존 처치 수와 같다).
- **검증**: `Editor/SyncFixtures/KillLedgerCheck`가 판마다 서버 결산 검증과 같은 식을 확인한다: S3(`시작 EXP + Σ(칸 수 × 칸 EXP) == Hq.Exp`), S6(`Σ(칸 수 × 칸 Gold) + GoldenCritBonusGold == EarnedGold`), `Σ칸 수 == TotalKills`, 칸 중복 없음, S5(`GoldenCritCount ≤ 황금 처치 수`), S7(`TimeBonusCount ≤ 수거형이 아닌 처치 수`). 칸의 Gold·EXP는 그 판의 수치 표(`World.Stats`)에서 읽는다. `HeadlessBattle.Run`이 결과의 `LedgerProblem`에 담고, 스모크·캠페인 보고서가 어긋난 판을 적고 `처치 장부(W3-1)` 줄로 요약한다.
- **확인 결과**(2026-10-14, 콘텐츠 `305af9bb49363c7e`): 메뉴 `Campaign Run`으로 600판(성장도 3에서 막힘으로 멈춤) 모두 맞았다. 판당 평균 칸 17.2개, 황금 치명 합 3,173번, 시간 추가 합 12,303번. 재현성 검사도 칸 수·치명 보너스·시간 추가 수까지 같다. Editor 테스트 104개 통과(`SyncLogFactoryTests`를 새 생성자에 맞췄다).

#### W0-2. 릴리즈 빌드 콘텐츠 버전 (S, 1일) — 완료(A가 진행)

- **목표**: 릴리즈 빌드에서도 `contentVersion`이 W0-1과 같은 값이 되게 한다(현재 `Playtest/ContentTag.cs`는 릴리즈에서 `""`). 빌드 전처리에서 W0-1과 같은 루틴으로 묶음을 메모리에서 만들어 계산한다(계약 1.3).
  - 에디터 실행(Play 모드)에서도 같은 값이어야 한다. 개발 중 동기화 확인을 에디터에서 하기 때문이다.
  - 내보내기 루틴: `BlackHole.ContentExport`의 `ServerContentExporter.Export(...)`(입력 모으기는 `Editor/ContentExport/ServerContentExport.Build()` 참고). 결과의 `ContentVersion`이 서버 목차의 값과 같으면 된다.
- **구현(2026-10-13, A)**: 빌드 전처리 대신 **실행 중에 계산**한다. 내보내기에 필요한 입력(시트 CSV, 노드 배치, 판 콘텐츠)이 실행 중에도 있고 변환 로직이 Unity에 의존하지 않기 때문이다. `GameContentLoader`가 콘텐츠를 불러올 때 `ServerContent.VersionOf(setup)`로 계산해 `LoadedContent.ServerContentVersion`에 둔다(약 15ms). 에디터 내보내기도 같은 입력(`ServerContent.SourceOf`)을 써서 둘이 어긋날 수 없다. 밸런스 프로필의 패치는 들지 않는다(프로필 실행은 오프라인, 계약 C10).
- **이 작업이 끝나야 실제 동기화가 동작한다.** 그 전까지 클라이언트 로그는 서버에서 `CONTENT_VERSION_MISMATCH`로 거부된다. 로그 생성기(W3-2)가 이 값을 로그에 싣는다(계획서 2.3).
- **선행**: W0-1(완료).

#### W3-4. 동기화 전송기 (M, 2.5일) — 완료

- **목표**: 앱 시작 시 로그인 후 대기 로그 일괄 전송(없으면 빈 배열). 온라인 중 로그가 생기면 즉시 전송하고, 실패하면 쌓아 두고 오프라인으로 전환한다. 응답 처리: `appliedThroughSeq`까지 삭제, 거부 시 남은 로그 폐기, 진행도 덮어쓰기(W3-3 경로), 다음 seq 맞춤, 불일치 경고 로그.
- **참조**: 계획서 7.2.
- **완료 조건**: 가짜 `ISyncApi`로 각 응답 경우 테스트, 이후 로컬 서버로 M1 시나리오 확인.
- **선행**: W2-3, W3-2, W3-3. 서버 통합은 W1-2 이후.
- **결과**(클라이언트 `feat/asyncServer`):
  - `BlackHole.Net`에 `SyncApi`(`ISyncApi` 구현)와 `SyncSender`(계약 5.8)를 두었다. 둘 다 Unity에 의존하지 않는다. 응답 반영 규칙(5.7)은 W3-3의 `ProgressStore`·`SyncLedger`가 그대로 맡는다.
  - `SyncApi`: 로그 글자를 그대로 이어 붙여 `POST /me/sync`를 보낸다. 401이면 `RefreshAfterUnauthorizedAsync` 후 같은 본문을 한 번 다시 보낸다. 네트워크 오류·타임아웃·5xx는 `Unreachable`, 400·413·그 밖의 4xx·형식이 맞지 않는 200은 `ProtocolError`(상태 코드와 오류 코드를 오류 로그로 남긴다). `GoOffline`은 부르지 않는다.
  - `SyncSender`: 시작 동기화(빈 배열 포함), `LogAppended`마다 즉시 전송, 동시에 하나만, 500개·1 MB로 나눠 보내기, 거부 시 멈춤, 실패 시 이번 실행 중단과 `GoOffline`. 오프라인 사유와 세부 동작은 계약 5.8의 구현 메모.
  - `GameBootstrap`: 서버를 쓰는 실행에서만 `SyncApi`·`SyncSender`를 만들고(같은 전송을 인증과 함께 쓴다), 로그인 뒤 시작 동기화까지 기다린 다음 타이틀로 간다(계약 2.8). 동기화 요청 타임아웃과 시작 대기 시간은 `ServerSettings.SyncTimeoutSeconds`.
- **리뷰에서 고친 것**(2026-10-16): 온라인 중 즉시 전송이 조건 반전으로 동작하지 않던 문제, 응답 seq가 보낸 것보다 크면 실패로 보아 매 실행 오프라인이 되던 검사, 동기화 요청 타임아웃(5초 → 10초), 계약에 없는 `OfflineReason.Unauthorized` 추가(되돌림), `SyncApi`와 `SyncSender`의 이중 `GoOffline`, 실패 원인이 남지 않던 로그, 서버 설정이 없으면 `Awake`에서 멈추던 연결, 게임 빌드에 들어간 테스트용 `FakeSyncApi`.
- **테스트**(Editor, 전체 156개 통과):
  - `SyncSenderTests` 27개: 가짜 `ISyncApi`와 실제 `SyncLedger`로 시작 동기화(빈 요청, 쌓인 로그, 오프라인, 시간 초과 후 늦은 응답, 빈 요청을 기다리는 동안 쌓인 로그), 즉시 전송과 보내는 중 쌓인 로그, 500개·1 MB 경계, 거부(남은 로그 폐기, 보내는 중 쌓인 로그까지 폐기, 알림 이벤트, `CONTENT_VERSION_MISMATCH`), drift, 서버가 장부보다 앞선 응답, 실패 세 가지와 예외, 나눠 보내는 도중의 실패.
  - `SyncSenderStoreTests` 3개: 실제 `ProgressStore`와 함께 — 로그를 저장한 뒤에 보내는지, 타이틀의 시작 동기화 결과(반영, 거부)가 다음 실행의 저장과 "계속"에 남는지.
  - `SyncApiTests` 17개: 기존 `FakeHttpTransport`로 요청 모양(본문 글자, 토큰, 타임아웃), 샘플 응답 읽기, 401 재시도(성공, 갱신 실패, 다시 401), 4xx·5xx·네트워크 오류·타임아웃·형식 오류. `GoOffline`을 부르지 않는지도 본다.
  - `SyncLocalServerTests`(Explicit): 실제 `SyncApi`·`SyncSender`·`ProgressStore`로 M1 시나리오. 로컬 서버에서 통과(2026-10-16): 첫 실행에 등록·로그인 → 시작 동기화 → `RESET`·판 시작·결산·구매가 즉시 전송(요청 2번, seq 4까지 반영, Gold 18) → 오프라인 실행에서 구매 로그(seq 5)만 저장 → 다음 실행의 시작 동기화로 반영(Gold 11, 노드 2개) → 다시 불러와도 대기 로그 없음.
- **알려진 한계**:
  - M1 테스트는 로그의 콘텐츠 버전을 서버 응답에서 받아 쓴다. 게임이 계산한 콘텐츠 버전(W0-2)이 서버와 같은지는 보지 않는다.
  - 업그레이드 화면에서 구매가 거부되어 진행도를 서버 값으로 덮어쓰면, 노드 트리의 표시는 다음에 다시 그릴 때까지 이전 값이다(구매 규칙은 덮어쓴 값으로 계산한다). 계약 5.7이 정하지 않은 경우라 W3-5에서 함께 정한다.

#### W3-5. 거부 알림 UI (S, 1일)

- **목표**: 마지막 동기화에 거부가 있으면 타이틀 또는 업그레이드 화면에 한 번 텍스트로 알린다(문구는 A0-7).
- **선행**: W3-4.

#### W4-1a. 이식 기대값 표 생성 (클라이언트, A) (1.5일)

- **목표**: 에디터 메뉴 명령(`Editor/SyncFixtures`)이 여러 진행도 상태(빈 진행도, 캠페인 중간 단계들, 전부 구매)에 대한 시작 적 수, 제한 시간, 시작 레벨, 칸별 골드·경험치, 마일스톤 보상을 JSON으로 내보낸다. **형식은 계약 1.7a로 확정했다**(성장도 stage와 산 노드 loadout을 나눠 담는다. loadout은 `empty`, `partial-10/25/50/75`, `all` 6종).
  - 봇이 필요 없다. 값은 `GameSessionFactory.Create`로 만든 세션에서 읽는다: 시작 적 수·제한 시간·시작 레벨은 `GameSession.StartInfo`, 칸별 골드·경험치는 `World.Stats`(`EnemyStatTable`), 마일스톤 보상은 `HqMilestone.RewardFor`.
  - 진행도 상태는 `ProgressSaveData` → `ProgressSave.Load` → `ProgressSave.Restore`(공개 경로)로 만든다. 캠페인 중간 단계는 성장도와 산 노드를 단계별로 정한 상태다.
  - 파일에 `contentVersion`을 넣는다. 서버 테스트(W4-1b)가 서버 콘텐츠 버전과 비교해, 다르면 "픽스처 재생성 필요"로 실패한다.
  - 실제 콘텐츠를 쓴다. 서버 콘텐츠를 클라이언트의 현재 콘텐츠로 갱신한 뒤에 내보낸다(2026-10-16 완료, 버전 `4ab481c6a60d6e49`).
  - 구현: 표를 만드는 코드는 Unity 무관 어셈블리 `BlackHole.ContentExport`에 두어 Editor 테스트가 검증하고, 에디터 메뉴(`Editor/SyncFixtures`)는 콘텐츠를 읽고 노드 상태를 정해 그 코드를 부른다. 시작 레벨별 `levelForExp`를 `Hq`로 읽기 위해 `Core/Hq`의 `InternalsVisibleTo`에 `BlackHole.ContentExport`를 더한다.
- **완료 조건**: 같은 콘텐츠로 두 번 내보내면 같은 바이트다. 파일의 `contentVersion`이 `ServerContent.VersionOf`와 같다. 클라이언트가 예외를 던지는 상태는 내보내기를 실패시킨다(건너뛰지 않는다). 내보낸 파일이 W4-1b를 통과하고, 서버 `Rounding`·합산 순서·황금 치명 보너스(float → double 곱)를 일부러 바꾸면 W4-1b가 실패한다.
- **선행**: W0-1(같은 콘텐츠 사용).

#### W4-2a. 캠페인 로그 생성 (2일)

- **목표**: 에디터 메뉴 명령(`Editor/SyncFixtures`)이 W3-0의 판 실행기와 구매 정책으로 빈 진행도부터 **봇이 닿는 곳까지** 돌면서, W3-2 생성기(`SyncLogFactory`)로 로그 전체를 파일로 내보낸다. 현재 봇(`NearestEnemyBot`)은 성장도 3까지 간다(아래 제약).
  - 로그 순서: 처음에 `RESET`, 판마다 `RUN_START` → `SETTLEMENT` → 그 뒤 구매 로그들. 골드가 이어지는 하나의 체인이다.
  - `IJsonSerializer`는 `UnityJsonSerializer`를 쓴다. 파일에 `contentVersion`을 넣는다(W4-1a와 같은 이유).
  - **소요 시간은 문제가 아니다**(W3-0 측정): 성장도 3까지 약 300판에 25초, 500판에 56초(평균 113ms). 판 상한이나 구간 축소는 필요 없다.
  - **제약(W3-0 측정, 2026-10-14)**: `NearestEnemyBot`은 성장도 3(Lv30)에 약 300판에 닿는다. 그 뒤에는 모든 노드를 사고도 Lv38에서 멈춰 마지막 이정표(Lv40, 성장도 4)에 닿지 못한다. 계속 돌리면 Gold가 쌓여 `long` 상한(약 9.2×10¹⁸)을 넘어 오버플로가 난다(판 996). 그래서 "클리어까지"를 전제로 하지 않고, **이정표 없이 N판이면 멈추는 규칙**(스모크 도구에 있다)으로 끝낸다.
  - **선택지**: (a) 골든 로그를 성장도 3까지로 한정한다(기본). 마지막 이정표 이후의 서버 분기(다음 이정표 없음, 목표 Level 0)는 골든 로그로 검증하지 못하므로 W1-6의 단위 테스트가 맡는다. (b) 봇을 강화해 마지막 이정표까지 보낸다. 삭제된 `GreedyAimBot`(479줄) 전체를 되살리지 않고 필요한 만큼만 개선한다. (c) `BattleCheats`로 EXP를 더하는 방법은 쓸 수 없다. 더한 EXP가 처치 장부에 없어 서버의 S3(EXP 일치) 검사와 어긋난다.
- **선행**: W3-0(완료), W3-1(완료), W3-2(완료).

#### W5-1. battle-summary 정리 (S, 1일)

- **목표**: battle-summary의 `battleId`를 `runId`와 같은 값으로 쓴다(D6). 서버 `feat/battleSummary` 브랜치의 처리(재작업 또는 폐기)는 A가 결정한다.
- **선행**: W3-2.

## 5. 충돌 주의 지점

같은 파일을 여러 명이 고치는 곳이다. 아래 담당이 해당 파일을 관리하고, 다른 담당은 그 담당이 열어 둔 연결 지점(메서드나 이벤트)만 사용한다.

| 파일 | 관리 | 다른 담당의 변경 |
|---|---|---|
| `Battle/GameBootstrap.cs` | C | A의 저장소·생성기 연결은 C에게 요청 |
| `UI/Flow/ScreenFlow.*.cs` | C | A의 로그 생성 4지점(W3-2)은 한 줄 호출만 추가 |
| `Save/*`, `Core/Nodes/Save/*` | A | - |
| `Core/Enemies/*`, `Core/Battle/World/*` | C(W3-1) | - |
| `Assets/Scripts/Net/`의 인터페이스·타입(계약 2.4) | A | C는 구현 클래스를 추가한다. 인터페이스 변경이 필요하면 A에게 요청 |
| `Assets/Scripts/Sync/`(`SyncLogFactory`), `Core/Battle/Session/RunStartInfo.cs` | A | - |
| `Core/Battle/Session/GameSession.cs`, `BattleRawData.cs` | 함께 | C는 `CreateRawData`와 `BattleRawData`(W3-1), A는 `StartInfo`(W3-2)만 고친다 |
| `Core/Enemies/KillBucket.cs` | A(타입) | C는 세는 코드에서 쓴다. 타입 변경이 필요하면 A에게 요청 |
| 서버 `sync/`(루트), `content/`(묶음 목차·버전) | A | B는 `sync/handler/`만. `content/`는 읽기만 |
| 서버 `battle/content/`, `battle/rules/` | B | A0-3 인터페이스 변경은 A |
| 서버 `db/migration/` | A | 추가 마이그레이션이 필요하면 A에게 요청 |

## 6. 공통 규칙

- 브랜치: 작업 ID별로 `feat/<id>-<짧은 이름>`(예: `feat/w1-3-purchase-handler`). `dev`로 PR, 리뷰어 1명 이상(A는 B·C의 PR을, B·C는 서로 또는 A의 PR을 리뷰).
- 커밋 메시지: 양쪽 저장소의 `.gitmessage` 형식(`type(scope): 설명`).
- 클라이언트 `CONTRIBUTING.md`의 브랜치 기준(`main`)과 이 규칙(`dev`)이 다르면 이 규칙을 따른다.
- 계약(A0)과 다른 동작이 필요하면 구현으로 우회하지 말고 A에게 알린다.
