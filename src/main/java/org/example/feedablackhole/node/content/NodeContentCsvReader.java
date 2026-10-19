package org.example.feedablackhole.node.content;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 노드 콘텐츠 시트 4개(CSV)를 행 데이터(NodeContentData)로 읽는다. 형식(머리칸·글자·숫자)만 보고, 규칙은 NodeContentLoader가 본다.
 * 칸은 머리칸 이름(영문, 대소문자 구분)으로 찾는다. 아래에 적은 필수 칸만 읽고, 나머지는 어디에 있든 읽지 않는다:
 * Memo(사람이 읽는 메모)나 그 오른쪽에 더한 분석용 칸은 코드에 영향이 없다. 칸 순서가 바뀌어도 된다.
 * 필수 칸 이름이 뒤쪽 칸에 한 번 더 나와도 앞쪽 칸을 읽는다. 읽는 칸이 모두 빈 행은 건너뛴다.
 * 진단 위치는 시트 좌표다("NodeCost!C12"). 소수는 float가 아니라 BigDecimal로 읽는다.
 */
public final class NodeContentCsvReader {

    public static final String STATS_TAB = "UpgradeStats";
    public static final String NODES_TAB = "Nodes";
    public static final String COST_TAB = "NodeCost";
    public static final String EFFECTS_TAB = "NodeEffects";

    // 필수 칸. 이 밖의 칸(Memo 포함)은 읽지 않는다.
    private static final String[] STATS_COLUMNS =
            {"StatId", "ValueType", "Unit", "DefaultValue", "Aggregation", "Min", "Max"};
    private static final String[] NODES_COLUMNS = {"NodeId", "RankCount"};
    private static final String[] COST_COLUMNS = {"NodeId", "Rank", "Cost"};
    private static final String[] EFFECTS_COLUMNS = {"NodeId", "Rank", "StatId", "Value", "Unit"};

    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");
    // 천 단위 쉼표("2,600,000")를 허용한다.
    private static final Pattern THOUSANDS_INTEGER = Pattern.compile("[+-]?\\d[\\d,]*");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d[\\d,]*(\\.\\d+)?|\\.\\d+)([eE][+-]?\\d+)?");
    // 클라이언트는 수치를 float로 읽으므로, float로 나타낼 수 없는 크기는 같은 이유로 거부한다.
    private static final BigDecimal FLOAT_LIMIT = new BigDecimal(Float.MAX_VALUE);

    private NodeContentCsvReader() {
    }

    /** 형식 오류는 into에 더하고, 그 칸은 0이나 빈 글자로 둔다. into가 늘었으면 결과를 쓰지 않는다. null 시트는 "없다"는 뜻이다. */
    public static NodeContentData read(
            String statsCsv, String nodesCsv, String costCsv, String effectsCsv, List<ContentDiagnostic> into) {
        Sheet stats = Sheet.open(STATS_TAB, statsCsv, STATS_COLUMNS, into);
        Sheet nodes = Sheet.open(NODES_TAB, nodesCsv, NODES_COLUMNS, into);
        Sheet costs = Sheet.open(COST_TAB, costCsv, COST_COLUMNS, into);
        Sheet effects = Sheet.open(EFFECTS_TAB, effectsCsv, EFFECTS_COLUMNS, into);

        List<NodeContentData.StatRow> statRows = new ArrayList<>();
        List<NodeContentData.NodeRow> nodeRows = new ArrayList<>();
        List<NodeContentData.CostRow> costRows = new ArrayList<>();
        List<NodeContentData.EffectRow> effectRows = new ArrayList<>();

        if (stats != null) {
            for (int r : stats.rows()) {
                statRows.add(new NodeContentData.StatRow(
                        Sheet.sheetRow(r),
                        stats.text(r, "StatId"),
                        stats.text(r, "ValueType"),
                        stats.text(r, "Unit"),
                        stats.decimal(r, "DefaultValue"),
                        stats.text(r, "Aggregation"),
                        stats.optionalDecimal(r, "Min"),
                        stats.optionalDecimal(r, "Max")));
            }
        }

        if (nodes != null) {
            for (int r : nodes.rows()) {
                nodeRows.add(new NodeContentData.NodeRow(
                        Sheet.sheetRow(r), nodes.text(r, "NodeId"), nodes.integer(r, "RankCount")));
            }
        }

        if (costs != null) {
            for (int r : costs.rows()) {
                costRows.add(new NodeContentData.CostRow(
                        Sheet.sheetRow(r), costs.text(r, "NodeId"), costs.integer(r, "Rank"), costs.longValue(r, "Cost")));
            }
        }

        if (effects != null) {
            for (int r : effects.rows()) {
                effectRows.add(new NodeContentData.EffectRow(
                        Sheet.sheetRow(r),
                        effects.text(r, "NodeId"),
                        effects.integer(r, "Rank"),
                        effects.text(r, "StatId"),
                        effects.decimal(r, "Value"),
                        effects.text(r, "Unit")));
            }
        }

        return new NodeContentData(statRows, nodeRows, costRows, effectRows);
    }

    /** 시트 하나: 머리칸 이름 → 칸 번호. 칸을 읽지 못하면 그 좌표로 진단을 더한다. */
    private static final class Sheet {

        private final String tab;
        private final List<String[]> rows;
        private final Map<String, Integer> columns;
        private final String[] required;
        private final List<ContentDiagnostic> into;

        private Sheet(String tab, List<String[]> rows, Map<String, Integer> columns, String[] required,
                List<ContentDiagnostic> into) {
            this.tab = tab;
            this.rows = rows;
            this.columns = columns;
            this.required = required;
            this.into = into;
        }

        /** 머리칸에 required가 모두 있어야 연다. 없으면 진단을 더하고 null이다. */
        static Sheet open(String tab, String csv, String[] required, List<ContentDiagnostic> into) {
            if (csv == null) {
                into.add(new ContentDiagnostic(tab, "시트(CSV)가 없다."));
                return null;
            }

            List<String[]> rows = CsvParser.parse(csv);

            if (rows.isEmpty()) {
                into.add(new ContentDiagnostic(tab,
                        "비어 있다. 첫 행에 머리칸(" + String.join(", ", required) + ")이 필요하다."));
                return null;
            }

            Map<String, Integer> columns = new HashMap<>();
            String[] header = rows.getFirst();
            for (int c = 0; c < header.length; c++) {
                String title = header[c].trim();
                if (!title.isEmpty()) {
                    columns.putIfAbsent(title, c);
                }
            }

            boolean complete = true;
            for (String title : required) {
                if (!columns.containsKey(title)) {
                    into.add(new ContentDiagnostic(tab + " 1행", "머리칸 '" + title + "'이 없다."));
                    complete = false;
                }
            }

            return complete ? new Sheet(tab, rows, columns, required, into) : null;
        }

        /** 표의 행 번호 → 시트 행 번호(머리칸이 1행). */
        static int sheetRow(int r) {
            return r + 1;
        }

        /** 머리칸 아래의 행 가운데, 읽는 칸이 모두 비지 않은 행. */
        List<Integer> rows() {
            List<Integer> result = new ArrayList<>();
            for (int r = 1; r < rows.size(); r++) {
                for (String column : required) {
                    if (!text(r, column).isEmpty()) {
                        result.add(r);
                        break;
                    }
                }
            }
            return result;
        }

        String text(int r, String column) {
            int c = columns.get(column);
            String[] row = rows.get(r);
            return c < row.length ? row[c].trim() : "";
        }

        int integer(int r, String column) {
            String text = text(r, column);
            if (INTEGER.matcher(text).matches()) {
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    // 범위를 벗어났다. 아래에서 진단한다.
                }
            }
            fail(r, column, text, "정수");
            return 0;
        }

        long longValue(int r, String column) {
            String text = text(r, column);
            if (THOUSANDS_INTEGER.matcher(text).matches()) {
                try {
                    return Long.parseLong(text.replace(",", ""));
                } catch (NumberFormatException e) {
                    // 범위를 벗어났다. 아래에서 진단한다.
                }
            }
            fail(r, column, text, "정수");
            return 0;
        }

        /** 소수는 점(.)으로 적는다. % 기호는 받지 않는다 — 값은 단위 칸이 정하고, Percent의 25는 25%다. */
        BigDecimal decimal(int r, String column) {
            String text = text(r, column);
            if (DECIMAL.matcher(text).matches()) {
                try {
                    BigDecimal value = new BigDecimal(text.replace(",", ""));
                    if (value.abs().compareTo(FLOAT_LIMIT) <= 0) {
                        return value;
                    }
                } catch (NumberFormatException e) {
                    // 지수가 너무 크다. 아래에서 진단한다.
                }
            }
            fail(r, column, text, "숫자(소수는 점으로, % 기호 없이)");
            return BigDecimal.ZERO;
        }

        /** 빈 칸은 null이다. */
        BigDecimal optionalDecimal(int r, String column) {
            return text(r, column).isEmpty() ? null : decimal(r, column);
        }

        private void fail(int r, String column, String text, String expected) {
            into.add(new ContentDiagnostic(cell(r, column),
                    text.isEmpty()
                            ? "비어 있다. " + expected + "가 필요하다."
                            : expected + "가 필요하다. 받은 값: '" + text + "'."));
        }

        private String cell(int r, String column) {
            return tab + "!" + letter(columns.get(column)) + sheetRow(r);
        }

        /** 칸 번호(0부터) → 시트 열 이름(A, B, …, Z, AA, …). */
        private static String letter(int column) {
            StringBuilder letters = new StringBuilder();
            for (int n = column + 1; n > 0; n = (n - 1) / 26) {
                letters.insert(0, (char) ('A' + (n - 1) % 26));
            }
            return letters.toString();
        }

    }

}
