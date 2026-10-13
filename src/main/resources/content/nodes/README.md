# 노드 콘텐츠 (클라이언트에서 내보낸 사본)

이 폴더의 파일은 클라이언트 저장소의 콘텐츠를 **내보내기 도구로 만든 사본**이다. 손으로 고치지 않는다.
고칠 때는 클라이언트 저장소에서 고친 뒤 Unity 에디터의 `BlackHole > Server Content > Export...`로 `src/main/resources/content/`에 다시 내보낸다.

| 파일 | 내용 | 클라이언트 원본 |
| --- | --- | --- |
| `UpgradeStats.csv` | 업그레이드 수치 정의(StatId, 단위, 기본값, Min·Max) | `Assets/Data/NodeTable/UpgradeStats.csv` |
| `Nodes.csv` | 노드와 Rank 수(RankCount) | `Assets/Data/NodeTable/Nodes.csv` |
| `NodeCost.csv` | (노드, Rank)마다 비용 | `Assets/Data/NodeTable/NodeCost.csv` |
| `NodeEffects.csv` | (노드, Rank)마다 효과(수치와 값) | `Assets/Data/NodeTable/NodeEffects.csv` |
| `layout.json` | 노드의 격자 칸, 시작 노드, 이어진 노드(선) | `Assets/Data/NodeCatalog.asset` |

형식과 규칙은 `docs/node-content.md`, 묶음 형식과 콘텐츠 버전은 `docs/server-sync-contracts.md` 1장을 본다.
