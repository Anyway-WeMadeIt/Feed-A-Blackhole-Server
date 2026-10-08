# 노드 콘텐츠 (서버의 원본)

이 폴더의 파일이 노드 콘텐츠의 **원본**이다. 서버는 시작할 때 한 번 읽어 검증하고 메모리에 보관한다.
규칙과 형식은 `docs/node-content.md`를 본다.

| 파일 | 내용 |
| --- | --- |
| `UpgradeStats.csv` | 업그레이드 수치 정의(StatId, 단위, 기본값, Min·Max) |
| `Nodes.csv` | 노드와 Rank 수(RankCount) |
| `NodeCost.csv` | (노드, Rank)마다 비용 |
| `NodeEffects.csv` | (노드, Rank)마다 효과(수치와 값) |
| `layout.json` | 노드의 격자 칸, 시작 노드, 이어진 노드(선) |

CSV는 필수 칸과 `Memo`로 이루어진다. `Memo`는 사람이 읽는 메모이고 코드가 읽지 않으며, `Memo` 오른쪽에 칸을 더해도 영향이 없다.

## 처음 가져온 곳

`tools/import_node_content.py`로 클라이언트 저장소에서 1회 이관했다. 이후 원본은 이 저장소다.

- 클라이언트 브랜치/커밋: `dev` / `4977e41`
- 이관일: 2026-10-08
- 노드 247개, 선 364개, 시작 노드 4개

클라이언트 저장소에 남아 있는 같은 파일(`Assets/Data/NodeTable`, `NodeCatalog.asset`)은 이관 시점의 사본이므로 고치지 않는다.
