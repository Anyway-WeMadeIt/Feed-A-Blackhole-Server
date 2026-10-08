package org.example.feedablackhole.node.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CsvParserTests {

    @Test
    void splitsRowsAndCells() {
        List<String[]> rows = CsvParser.parse("a,b,c\n1,2,3\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("a", "b", "c");
        assertThat(rows.get(1)).containsExactly("1", "2", "3");
    }

    @Test
    void quotedCellsMayContainCommasQuotesAndNewlines() {
        List<String[]> rows = CsvParser.parse("x,\"2,600,000\",\"say \"\"hi\"\"\",\"line1\nline2\"\n");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly("x", "2,600,000", "say \"hi\"", "line1\nline2");
    }

    @Test
    void handlesCrLfLineEndingsAndMissingFinalNewline() {
        List<String[]> rows = CsvParser.parse("a,b\r\n1,2");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("1", "2");
    }

    @Test
    void ignoresLeadingBom() {
        List<String[]> rows = CsvParser.parse("\uFEFFNodeId,Rank\n");

        assertThat(rows.get(0)).containsExactly("NodeId", "Rank");
    }

    @Test
    void keepsEmptyCellsAndEmptyTrailingCell() {
        List<String[]> rows = CsvParser.parse("a,,c,\n");

        assertThat(rows.get(0)).containsExactly("a", "", "c", "");
    }

    @Test
    void emptyTextHasNoRows() {
        assertThat(CsvParser.parse("")).isEmpty();
    }

}
