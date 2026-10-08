#!/usr/bin/env python3
"""클라이언트 저장소의 노드 데이터를 서버의 콘텐츠 폴더로 이관한다(1회용 기록).

원본은 이관한 뒤부터 서버 저장소의 src/main/resources/content/nodes/ 파일이다.
이 스크립트는 그 파일들을 처음 만든 방법을 남겨 두려는 용도이며, 클라이언트 저장소는 읽기만 한다.

  - CSV 4개: 시트에서 내려받은 CSV에서 필수 칸과 Memo만 남기고, 칸 이름을 영문으로 통일한다.
    칸 안의 값은 바꾸지 않는다(천 단위 쉼표 같은 표기도 그대로). 아래 CSV_PLAN이 새 칸과 옛 칸의 대응이다.
      Nodes.csv        : Rank 수 → RankCount
      NodeEffects.csv  : 단위 → Unit, 표시 칸 삭제
      UpgradeStats.csv : Enabled(전부 TRUE였다)와 분석용 칸(원작 이름 등) 삭제
      NodeCost.csv, NodeEffects.csv: 빈 Memo 칸을 더한다
  - layout.json: Unity 노드 도구가 만드는 NodeCatalog.asset(YAML)의 배치를, Unity JsonUtility가 NodeTreeData를
    내보낼 때와 같은 필드 이름(Nodes, Id, Start, X, Y, Links)의 JSON으로 바꾼다. 노드 한 개가 한 줄이다.

이미 파일이 있는 폴더에는 --force 없이 쓰지 않는다(이관 뒤에는 서버의 파일이 원본이라 고쳐졌을 수 있다).

사용: python tools/import_node_content.py [--client <클라이언트 저장소 경로>] [--force]
표준 라이브러리만 쓴다(Python 3.8+).
"""
import argparse
import csv
import datetime
import json
import re
import subprocess
import sys
from pathlib import Path

CSV_DIR = Path("Assets/Data/NodeTable")
CATALOG = Path("Assets/Data/NodeCatalog.asset")

# 서버 파일 이름 → [(새 칸 이름, 클라이언트 시트의 옛 칸 이름 또는 None(빈 칸))]
CSV_PLAN = {
    "UpgradeStats.csv": [("StatId", "StatId"), ("ValueType", "ValueType"), ("Unit", "Unit"),
                         ("DefaultValue", "DefaultValue"), ("Aggregation", "Aggregation"),
                         ("Min", "Min"), ("Max", "Max"), ("Memo", "Memo")],
    "Nodes.csv": [("NodeId", "NodeId"), ("RankCount", "Rank 수"), ("Memo", "Memo")],
    "NodeCost.csv": [("NodeId", "NodeId"), ("Rank", "Rank"), ("Cost", "Cost"), ("Memo", None)],
    "NodeEffects.csv": [("NodeId", "NodeId"), ("Rank", "Rank"), ("StatId", "StatId"), ("Value", "Value"),
                        ("Unit", "단위"), ("Memo", None)],
}
# 코드가 읽는 칸(옛 이름). 이 칸이 모두 빈 행은 코드도 건너뛴다.
READ_COLUMNS = {
    "UpgradeStats.csv": ["StatId", "ValueType", "Unit", "DefaultValue", "Aggregation", "Min", "Max"],
    "Nodes.csv": ["NodeId", "Rank 수"],
    "NodeCost.csv": ["NodeId", "Rank", "Cost"],
    "NodeEffects.csv": ["NodeId", "Rank", "StatId", "Value", "단위"],
}


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--client", type=Path, default=repo.parent / "Feed-A-Blackhole-Client",
                        help="클라이언트 저장소 경로(기본: 서버 저장소 옆의 Feed-A-Blackhole-Client)")
    parser.add_argument("--out", type=Path, default=repo / "src/main/resources/content/nodes",
                        help="내보낼 폴더")
    parser.add_argument("--force", action="store_true", help="이미 파일이 있는 폴더에도 덮어쓴다")
    args = parser.parse_args()

    client = args.client.resolve()
    out = args.out.resolve()
    if not (client / CATALOG).exists():
        print(f"클라이언트 저장소를 찾을 수 없다: {client}", file=sys.stderr)
        return 1
    if out.exists() and any(out.iterdir()) and not args.force:
        print(f"{out}에 이미 파일이 있다. 이관 뒤에는 이 파일이 원본이라 덮어쓰지 않는다(정말 다시 만들려면 --force).",
              file=sys.stderr)
        return 1

    layout = parse_catalog((client / CATALOG).read_text(encoding="utf-8"))
    problems = verify(layout, client)
    if problems:
        print("변환 결과가 이상하다:", *problems, sep="\n  - ", file=sys.stderr)
        return 1

    out.mkdir(parents=True, exist_ok=True)
    for name in CSV_PLAN:
        write_csv(client / CSV_DIR / name, out / name, name)
    (out / "layout.json").write_text(render_layout(layout), encoding="utf-8", newline="\n")
    (out / "README.md").write_text(render_readme(client, layout), encoding="utf-8", newline="\n")

    print(f"이관 완료: {out}")
    print(f"  노드 {len(layout)}개, 선 {len(links(layout))}개, 시작 노드 {sum(1 for n in layout if n['Start'])}개")
    return 0


def write_csv(source: Path, target: Path, name: str) -> None:
    """필수 칸과 Memo만 남기고 칸 이름을 영문으로 바꿔 쓴다. 칸 안의 값은 그대로다."""
    with source.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    kept = [row for row in rows if any((row.get(column) or "").strip() for column in READ_COLUMNS[name])]
    columns = CSV_PLAN[name]
    with target.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow([new for new, _ in columns])
        for row in kept:
            writer.writerow([(row.get(old) or "") if old else "" for _, old in columns])


def parse_catalog(text: str) -> list:
    """NodeCatalog.asset의 _layout.Nodes를 읽는다. 노드 도구가 쓰는 YAML 형식(고정된 들여쓰기)만 지원한다."""
    if "_layout:" not in text:
        raise SystemExit("NodeCatalog.asset에서 _layout을 찾지 못했다.")
    body = text.split("_layout:", 1)[1]

    nodes = []
    current = None
    for line in body.splitlines():
        if m := re.match(r"^    - Id: (\S+)$", line):
            current = {"Id": m.group(1), "Start": None, "X": None, "Y": None, "Links": []}
            nodes.append(current)
        elif current is None:
            continue
        elif m := re.match(r"^      Start: ([01])$", line):
            current["Start"] = m.group(1) == "1"
        elif m := re.match(r"^      X: (-?\d+)$", line):
            current["X"] = int(m.group(1))
        elif m := re.match(r"^      Y: (-?\d+)$", line):
            current["Y"] = int(m.group(1))
        elif m := re.match(r"^      - (\S+)$", line):
            current["Links"].append(m.group(1))
    return nodes


def links(layout: list) -> set:
    return {frozenset((n["Id"], other)) for n in layout for other in n["Links"]}


def verify(layout: list, client: Path) -> list:
    """변환이 노드 데이터를 잃지 않았는지 확인한다(규칙 검사는 서버의 로더가 한다)."""
    problems = []
    ids = [n["Id"] for n in layout]
    if len(ids) != len(set(ids)):
        problems.append("노드 ID가 중복이다.")
    for n in layout:
        for field in ("Start", "X", "Y"):
            if n[field] is None:
                problems.append(f"{n['Id']}: {field}를 읽지 못했다.")
    known = set(ids)
    for n in layout:
        for other in n["Links"]:
            if other not in known:
                problems.append(f"{n['Id']}: 선이 가리키는 노드 {other}가 없다.")

    with (client / CSV_DIR / "Nodes.csv").open(encoding="utf-8-sig", newline="") as f:
        content_ids = {row["NodeId"].strip() for row in csv.DictReader(f) if row["NodeId"].strip()}
    if known != content_ids:
        problems.append(f"배치의 노드와 Nodes.csv의 노드가 다르다. 배치에만: {sorted(known - content_ids)[:5]}, "
                        f"시트에만: {sorted(content_ids - known)[:5]}")

    # YAML에서 실제로 읽은 노드 수와 비교한다(정규식이 놓친 노드가 없는지).
    expected = len(re.findall(r"^    - Id: ", (client / CATALOG).read_text(encoding="utf-8"), flags=re.M))
    if expected != len(layout):
        problems.append(f"YAML의 노드는 {expected}개인데 {len(layout)}개만 읽었다.")
    return problems


def render_layout(layout: list) -> str:
    """노드 한 개를 한 줄로 쓴다(diff에서 노드 단위로 읽기 쉽다)."""
    lines = []
    for n in layout:
        item = json.dumps(n, ensure_ascii=False, separators=(", ", ": "))
        lines.append("    " + item)
    return '{\n  "Nodes": [\n' + ",\n".join(lines) + "\n  ]\n}\n"


def render_readme(client: Path, layout: list) -> str:
    def git(*cmd: str) -> str:
        try:
            return subprocess.check_output(["git", "-C", str(client), *cmd], text=True).strip()
        except (OSError, subprocess.CalledProcessError):
            return "알 수 없음"

    today = datetime.date.today().isoformat()
    return f"""# 노드 콘텐츠 (서버의 원본)

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

- 클라이언트 브랜치/커밋: `{git('branch', '--show-current')}` / `{git('rev-parse', '--short', 'HEAD')}`
- 이관일: {today}
- 노드 {len(layout)}개, 선 {len(links(layout))}개, 시작 노드 {sum(1 for n in layout if n['Start'])}개

클라이언트 저장소에 남아 있는 같은 파일(`Assets/Data/NodeTable`, `NodeCatalog.asset`)은 이관 시점의 사본이므로 고치지 않는다.
"""


if __name__ == "__main__":
    sys.exit(main())
