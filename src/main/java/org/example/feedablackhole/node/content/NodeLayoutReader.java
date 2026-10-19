package org.example.feedablackhole.node.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 노드 배치 파일(layout.json)을 NodePlacement 목록으로 읽는다. 형식만 보고, 규칙은 NodeTreeLoader가 본다.
 *
 * <p>형식은 Unity 노드 도구의 NodeTreeData를 JsonUtility로 내보낸 것과 같다(필드 이름의 대소문자까지):
 * <pre>
 * { "Nodes": [ { "Id": "timer-01", "Start": true, "X": 0, "Y": 3, "Links": ["timer-02"] }, ... ] }
 * </pre>
 * Id, X, Y는 필수이고 Start는 없으면 false, Links는 없으면 빈 목록이다. 알 수 없는 필드는 무시한다.
 */
public final class NodeLayoutReader {

    public static final String FILE = "layout.json";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private NodeLayoutReader() {
    }

    /** 형식 오류는 into에 더한다. 읽을 수 없는 노드는 목록에서 뺀다. null은 파일이 없다는 뜻이다. */
    public static List<NodePlacement> read(String json, List<ContentDiagnostic> into) {
        if (json == null) {
            into.add(new ContentDiagnostic(FILE, "배치 파일이 없다."));
            return List.of();
        }

        Object root;
        try {
            root = MAPPER.readValue(json, Object.class);
        } catch (JacksonException e) {
            into.add(new ContentDiagnostic(FILE, "JSON 형식이 올바르지 않다: " + firstLine(e.getMessage())));
            return List.of();
        }

        if (!(root instanceof Map<?, ?> object) || !(object.get("Nodes") instanceof List<?> items)) {
            into.add(new ContentDiagnostic(FILE, "{\"Nodes\": [ ... ]} 형태여야 한다."));
            return List.of();
        }

        List<NodePlacement> placements = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            NodePlacement placement = readNode(items.get(i), i, into);
            if (placement != null) {
                placements.add(placement);
            }
        }
        return placements;
    }

    private static NodePlacement readNode(Object item, int index, List<ContentDiagnostic> into) {
        String at = FILE + " Nodes[" + index + "]";

        if (!(item instanceof Map<?, ?> node)) {
            into.add(new ContentDiagnostic(at, "노드는 { } 객체여야 한다."));
            return null;
        }

        int before = into.size();
        String id = node.get("Id") instanceof String text && !text.isBlank() ? text : null;
        if (id == null) {
            into.add(new ContentDiagnostic(at, "Id는 비어 있지 않은 글자여야 한다."));
        } else {
            at = FILE + " Nodes[" + id + "]";
        }

        boolean start = false;
        if (node.containsKey("Start")) {
            if (node.get("Start") instanceof Boolean flag) {
                start = flag;
            } else {
                into.add(new ContentDiagnostic(at, "Start는 true 또는 false여야 한다."));
            }
        }

        int x = integer(node, "X", at, into);
        int y = integer(node, "Y", at, into);

        List<String> links = new ArrayList<>();
        if (node.containsKey("Links")) {
            if (node.get("Links") instanceof List<?> list) {
                for (int k = 0; k < list.size(); k++) {
                    if (list.get(k) instanceof String link) {
                        links.add(link);
                    } else {
                        into.add(new ContentDiagnostic(at + ".Links[" + k + "]", "노드 ID(글자)여야 한다."));
                    }
                }
            } else {
                into.add(new ContentDiagnostic(at, "Links는 노드 ID의 목록이어야 한다."));
            }
        }

        return into.size() > before ? null : new NodePlacement(id, start, x, y, links);
    }

    private static int integer(Map<?, ?> node, String field, String at, List<ContentDiagnostic> into) {
        if (node.get(field) instanceof Integer value) {
            return value;
        }
        into.add(new ContentDiagnostic(at, field + "는 정수여야 한다."));
        return 0;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

}
