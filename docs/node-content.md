# 노드 콘텐츠

노드 트리(어떤 노드가 있고, 어디에 놓이고, 얼마이고, 무엇을 주는가)의 **원본은 이 저장소의 파일**이다.
서버는 시작할 때 한 번 읽어 검증하고 불변 객체로 메모리에 보관한다. 요청을 처리하는 동안에는 파일을 읽지 않는다.
콘텐츠에 문제가 하나라도 있으면 **모든 문제를 한 번에 알리고 서버를 시작하지 않는다**(잘못된 콘텐츠로 구매를 받는 일이 없게).

- 위치: `src/main/resources/content/nodes/`
- 코드: `org.example.feedablackhole.node.content`(읽기·검증, 프레임워크 무관), `node.config.NodeContentConfig`(시작 때 불러오기)
- 클라이언트는 노드의 비용·효과·해금 규칙을 가지지 않는다. 서버가 계산한 결과만 받아 그린다([api-spec.md](api-spec.md)).

## 파일

| 파일 | 내용 | 편집 방법 |
|---|---|---|
| `UpgradeStats.csv` | 업그레이드 수치 정의 | 시트에서 고쳐 CSV로 내려받아 교체 |
| `Nodes.csv` | 노드와 Rank 수 | 같음 |
| `NodeCost.csv` | (노드, Rank)마다 비용 | 같음 |
| `NodeEffects.csv` | (노드, Rank)마다 효과 | 같음 |
| `layout.json` | 노드의 격자 칸, 시작 노드, 선 | Unity 노드 도구의 JSON 내보내기 또는 직접 편집 |

## 칸 규칙

CSV는 **필수 칸 + `Memo`** 로 이루어지고, 머리칸 이름은 영문이다(대소문자 구분).

- 코드는 아래 표의 **필수 칸만 이름으로 찾아** 읽는다. 칸 순서가 바뀌어도 된다.
- `Memo`는 사람이 읽는 메모다. 코드가 읽지 않으므로 없어도, 어떤 내용(쉼표, 따옴표, 줄바꿈)이 들어 있어도 된다.
- **`Memo` 오른쪽(또는 어디든)에 칸을 더해도 영향이 없다**(분석용 칸 등). 필수 칸과 같은 이름의 칸이 뒤에 더 있어도 앞쪽 칸을 읽는다.
  `nodeContentVersion`도 바뀌지 않는다(아래).
- 읽는 칸이 모두 빈 행은 건너뛰고, 행이 머리칸보다 짧으면 빠진 칸은 빈 칸으로 본다.
- 필수 칸의 이름을 바꾸거나 빼면 `머리칸 'RankCount'이 없다.`처럼 알린다. 한글 머리칸이던 `Rank 수`는 `RankCount`, `단위`는 `Unit`이다.

### UpgradeStats.csv

| 칸 | 설명 |
|---|---|
| `StatId` | 수치 이름(예: `breaker.critChance`). 유일. **클라이언트 코드와의 계약이다(아래)** |
| `ValueType` | `Float` 또는 `Int`(대소문자 그대로). Int 수치는 기본값·Min·Max와 효과 값이 모두 정수 |
| `Unit` | `Flat` 또는 `Percent`. 값은 시트에 적힌 그대로다(Percent의 25는 25%) |
| `DefaultValue` | 효과가 없을 때의 값. 늘어난 양의 기준점이며 Min·Max 자르기에 쓴다 |
| `Aggregation` | `Add`만 쓸 수 있다 |
| `Min`, `Max` | 값의 하한과 상한. 비우면 제한 없음. 수치 값 = (기본값 + 산 효과의 합)을 [Min, Max]로 자른 것 |
| `Memo` | 메모(코드가 읽지 않음) |

### Nodes.csv, NodeCost.csv, NodeEffects.csv

| 시트 | 칸 |
|---|---|
| `Nodes.csv` | `NodeId`, `RankCount`(1 이상), `Memo` |
| `NodeCost.csv` | `NodeId`, `Rank`, `Cost`(**0보다 큰** 정수, `long`. 천 단위 쉼표 `"2,600,000"` 허용), `Memo` |
| `NodeEffects.csv` | `NodeId`, `Rank`, `StatId`, `Value`(소수는 점으로, `%` 기호 없이), `Unit`(수치 정의의 `Unit`과 같아야 함), `Memo` |

노드는 Rank를 1부터 차례로 하나씩 산다. 같은 (노드, Rank)에 효과가 여럿이어도 된다(지금은 하나씩이다).

### layout.json

```json
{
  "Nodes": [
    {"Id": "timer-01", "Start": true, "X": 0, "Y": 3, "Links": ["breaker.damage-01"]},
    {"Id": "breaker.damage-01", "Start": false, "X": 1, "Y": 3, "Links": []}
  ]
}
```

- 필드 이름과 대소문자는 Unity 노드 도구의 `NodeTreeData`를 `JsonUtility`로 내보낸 것과 같다(`Nodes`, `Id`, `Start`, `X`, `Y`, `Links`).
  그래서 노드 도구에서 `JsonUtility.ToJson(catalog.ToData(), true)`로 바로 내보낼 수 있다.
- `Id`, `X`, `Y`는 필수. `Start`는 없으면 `false`, `Links`는 없으면 빈 목록. 알 수 없는 필드는 무시한다.
- 선은 방향이 없다. 한쪽 노드에만 적어도, 양쪽에 적어도 같은 선 하나다.

## 검증 규칙

클라이언트의 로더와 같은 규칙이다(오류는 모아서 한 번에 알리고, 하나라도 있으면 아무것도 불러오지 않는다).
진단의 위치는 시트 좌표(`NodeCost!C12`), 시트 행(`NodeCost 12행`), 노드(`Nodes[timer-01]`) 중 하나로 나온다.

| 단계 | 규칙 |
|---|---|
| 형식 | 시트와 필수 머리칸이 있음, 숫자 형식, 비용은 `long` 범위, 소수는 `float`로 나타낼 수 있는 크기, layout.json이 올바른 JSON |
| 수치 | `StatId` 유일, ValueType·Unit 이름, Aggregation은 Add뿐, Min ≤ Max, DefaultValue는 Min·Max 안, Int 수치는 정수 |
| 노드 | `NodeId` 유일, RankCount ≥ 1 |
| 비용 | Nodes 시트에 있는 노드, Rank는 1 ~ RankCount, (노드, Rank)마다 하나, 0보다 큼 |
| 효과 | 노드·Rank는 비용과 같은 기준, UpgradeStats에 있는 StatId, `Unit`이 수치의 `Unit`과 같음, Int 수치면 정수 |
| 빠짐 | 모든 노드의 모든 Rank에 비용과 효과가 있음 |
| 배치와 콘텐츠 | 배치된 노드는 Nodes 시트에 있음 |
| 배치 | ID 중복 없음, 선이 가리키는 노드가 배치에 있음, 자기 자신과 잇지 않음, **한 칸에 노드 하나**(서버가 더한 규칙) |
| 그래프 | 시작 노드가 하나 이상, 모든 배치 노드가 시작 노드에서 선을 따라 닿음 |

콘텐츠에만 있고 배치되지 않은 노드는 오류가 아니다. 트리에서 빠지고(살 수 없다) 시작할 때 경고로 알린다.

## 수치 ID는 클라이언트 코드와의 계약이다

클라이언트는 수치를 코드의 목록(`UpgradeStat` enum)으로 읽고, **시트에 그 수치가 모두 한 번씩 있어야** 불러온다.
서버가 내려주는 수치 ID로 클라이언트가 전투 값을 읽으므로, 수치를 더하거나 이름을 바꾸는 것은 클라이언트와 함께 정하는 변경이다.

서버는 클라이언트 코드를 모르므로 이 계약을 `RealNodeContentTests.CLIENT_STAT_IDS`(60개, 시트 순서)로 고정해 둔다.
수치 ID를 바꾸면 이 테스트가 실패해 변경을 알린다. 의도한 변경이면 클라이언트와 합의한 뒤 목록을 같이 고친다.

## 노드 ID와 플레이어 데이터

플레이어의 산 노드(`player_node_rank.node_id`)는 노드 ID 문자열로 콘텐츠를 가리킨다(FK 없음).

- **노드 ID는 바꾸지 않는다.** 바꾸면 플레이어가 산 기록이 끊긴다.
- 노드를 콘텐츠에서 빼거나 Rank 수를 줄여도 플레이어의 행은 지우지 않는다. 서버는 **모르는 노드를 건너뛰고, Rank는 최대 Rank까지만** 계산에 쓴다.

## 노드 콘텐츠 버전 (`nodeContentVersion`)

`nodeContentVersion`은 파일의 글자가 아니라 **불러온 의미**(수치 정의, 노드와 Rank의 비용·효과, 배치, 선, 그 순서)의 SHA-256 앞 16자리다.

- 의미가 같은 편집은 버전을 바꾸지 않는다: `Memo` 수정, 칸 추가, 줄바꿈 종류, BOM, 같은 값의 다른 표기(`12`와 `12.0`),
  선을 어느 쪽 노드에 적었는지, layout.json의 서식.
- 비용, 효과, 수치 정의, 노드의 칸과 시작 여부, 선, 노드 순서가 바뀌면 달라진다.

서버 시작 로그에 찍히고, 노드 목록 응답에 실어 클라이언트가 변하지 않는 부분을 캐시할 수 있게 한다(메모만 고쳤다고 캐시가 버려지지 않게).
(전투의 `contentVersion`은 클라이언트에 번들된 전투 데이터의 별개 버전이다. 이름이 겹쳐 혼동되지 않도록 노드 쪽은 `nodeContentVersion`이라고 부른다.)

## 콘텐츠를 고치는 방법

1. 시트에서 고쳐 CSV로 내려받아 이 폴더의 같은 이름 파일을 교체한다. 배치는 `layout.json`을 고친다.
2. `.\gradlew.bat test --tests "*RealNodeContentTests"`로 검증한다. 규칙을 어기면 어떤 시트의 몇 행이 왜 틀렸는지 모두 나온다.
3. PR을 올린다. CI의 테스트가 같은 검증을 하므로 잘못된 콘텐츠는 병합되지 않는다.
4. 배포하면 서버가 새 콘텐츠로 시작한다(콘텐츠 변경은 재배포가 필요하다).

## 숫자 처리

효과와 수치 값은 `BigDecimal`로 읽어 시트의 십진수를 그대로 합산한다(소수가 들어와도 부동소수점 오차가 쌓이지 않는다).
비용은 `long`이다(최댓값 1e15). 클라이언트가 `float`로 읽으므로 `float`로 나타낼 수 없는 크기의 값은 형식 오류다.

## 이관 이력

처음에는 클라이언트 저장소(`Assets/Data/NodeTable/*.csv`, `NodeCatalog.asset`)에 있던 것을 `tools/import_node_content.py`로 한 번 옮겼다
(출처 커밋은 `src/main/resources/content/nodes/README.md`). 이후 원본은 이 저장소이고, 클라이언트 쪽 사본은 서버 연동으로 전환할 때까지
**고치지 않고 동결**한다(고쳐도 서버에는 반영되지 않는다).
