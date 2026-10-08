package org.example.feedablackhole.node.content;

import java.util.Optional;

/**
 * 수치의 단위. 시트의 Unit(단위) 칸 글자(Flat, Percent)와 대소문자까지 같아야 한다.
 * 값은 시트에 적힌 그대로다: Percent의 25는 25%다. 비율(0.25)로 바꾸는 일은 값을 읽는 쪽이 한다.
 */
public enum StatUnit {

    FLAT("Flat"),
    PERCENT("Percent");

    private final String sheetName;

    StatUnit(String sheetName) {
        this.sheetName = sheetName;
    }

    public String sheetName() {
        return sheetName;
    }

    public static Optional<StatUnit> fromSheetName(String text) {
        for (StatUnit unit : values()) {
            if (unit.sheetName.equals(text)) {
                return Optional.of(unit);
            }
        }
        return Optional.empty();
    }

}
