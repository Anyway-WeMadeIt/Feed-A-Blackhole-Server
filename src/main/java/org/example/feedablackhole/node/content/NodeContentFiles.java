package org.example.feedablackhole.node.content;

/**
 * 노드 콘텐츠 파일 5개의 내용(글자). 파일을 어디서 읽는지는 모른다(서버는 클래스패스, 테스트는 문자열).
 * null은 그 파일이 없다는 뜻이며, 불러올 때 진단이 된다.
 */
public record NodeContentFiles(
        String statsCsv,
        String nodesCsv,
        String costCsv,
        String effectsCsv,
        String layoutJson) {

    public static final String STATS_FILE = NodeContentCsvReader.STATS_TAB + ".csv";
    public static final String NODES_FILE = NodeContentCsvReader.NODES_TAB + ".csv";
    public static final String COST_FILE = NodeContentCsvReader.COST_TAB + ".csv";
    public static final String EFFECTS_FILE = NodeContentCsvReader.EFFECTS_TAB + ".csv";
    public static final String LAYOUT_FILE = NodeLayoutReader.FILE;

}
