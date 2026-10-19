package org.example.feedablackhole.node.content;

import java.util.Optional;

/**
 * 수치의 값 종류. 시트의 ValueType 칸 글자(Float, Int)와 대소문자까지 같아야 한다.
 */
public enum StatValueType {

    FLOAT("Float"),
    INT("Int");

    private final String sheetName;

    StatValueType(String sheetName) {
        this.sheetName = sheetName;
    }

    public String sheetName() {
        return sheetName;
    }

    public static Optional<StatValueType> fromSheetName(String text) {
        for (StatValueType type : values()) {
            if (type.sheetName.equals(text)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

}
