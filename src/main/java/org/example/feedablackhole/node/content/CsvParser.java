package org.example.feedablackhole.node.content;

import java.util.ArrayList;
import java.util.List;

/**
 * 스프레드시트가 주고받는 CSV(RFC 4180): 쉼표로 칸을 나누고, 쉼표·큰따옴표·줄바꿈이 든 칸은 큰따옴표로 감싼다.
 * 맨 앞의 BOM은 무시하고, \r은 버린다. 클라이언트의 Csv.Parse와 같은 규칙이다.
 */
public final class CsvParser {

    private CsvParser() {
    }

    public static List<String[]> parse(String text) {
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int start = !text.isEmpty() && text.charAt(0) == '\uFEFF' ? 1 : 0;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (quoted) {
                if (c != '"') {
                    cell.append(c);
                } else if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = false;
                }
                continue;
            }

            switch (c) {
                case '"' -> quoted = true;
                case ',' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                }
                case '\r' -> {
                    // 줄바꿈은 \n 하나로 본다.
                }
                case '\n' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row.toArray(new String[0]));
                    row.clear();
                }
                default -> cell.append(c);
            }
        }

        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row.toArray(new String[0]));
        }

        return rows;
    }

}
