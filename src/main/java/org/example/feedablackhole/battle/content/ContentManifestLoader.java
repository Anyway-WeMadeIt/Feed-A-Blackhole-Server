package org.example.feedablackhole.battle.content;

import org.example.feedablackhole.content.ContentManifest;
import org.example.feedablackhole.node.content.ContentDiagnostic;
import org.example.feedablackhole.node.content.ContentLoadException;
import org.example.feedablackhole.node.content.NodeContentFiles;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 콘텐츠 묶음의 목차(manifest.json)를 읽어 ContentManifest로 만든다(계약 1.3).
 *
 * <p>검사하는 것:
 * <ol>
 * <li>목차 파일이 있고 JSON 형식이 올바르다
 * <li>formatVersion이 지원하는 값(1)이다
 * <li>contentVersion이 비어 있지 않다
 * <li>로더가 이름으로 읽는 파일이 files 목록에 모두 있다
 * <li>files 목록의 파일이 실제로 모두 있다
 * </ol>
 * contentVersion이 파일 내용과 맞는지는 여기서 보지 않는다(다음 단계에서 파일 바이트로 다시 계산해 비교한다).
 * 문제는 모아서 ContentLoadException 하나로 던진다.
 */
public final class ContentManifestLoader {

    // 예외 메시지에 쓰는 이름 ("콘텐츠 목차을(를) 불러오지 못했다")
    private static final String WHAT = "콘텐츠 목차";
    private static final String FILE = ContentManifest.FILE; // "manifest.json"

    // manifest.json의 키 이름
    private static final String FORMAT_VERSION = "formatVersion";
    private static final String CONTENT_VERSION = "contentVersion";
    private static final String FILES = "files";

    // content/ 아래 폴더 경로(목차의 files에 적히는 상대 경로의 앞부분)
    private static final String NODES_DIR = "nodes/";
    private static final String BATTLE_DIR = "battle/";

    // 로더가 이름으로 읽는 파일(content/ 기준). 목록에 없으면 contentVersion이 그 파일을 덮지 못한다
    // 파일 이름은 각 로더가 가진 상수를 그대로 써서, 이름이 바뀌면 그쪽만 고치면 된다
    private static final List<String> REQUIRED_FILES = List.of(
            NODES_DIR + NodeContentFiles.STATS_FILE,    // nodes/UpgradeStats.csv
            NODES_DIR + NodeContentFiles.NODES_FILE,    // nodes/Nodes.csv
            NODES_DIR + NodeContentFiles.COST_FILE,     // nodes/NodeCost.csv
            NODES_DIR + NodeContentFiles.EFFECTS_FILE,  // nodes/NodeEffects.csv
            NODES_DIR + NodeContentFiles.LAYOUT_FILE,   // nodes/layout.json
            BATTLE_DIR + BattleContentLoader.FILE);     // battle/battle.json

    // 목차에는 소수가 없어서 기본 설정으로 충분하다
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ContentManifestLoader() {
    }

    /**
     * 목차를 읽어 검증한다.
     *
     * @param source 묶음의 파일을 읽는 곳
     * @throws ContentLoadException 목차가 없거나 형식이 틀렸거나, 규칙을 어긴 곳이 하나라도 있을 때
     */
    public static ContentManifest load(BundleSource source) {
        // 1) 목차 파일이 있어야 한다. 없으면 더 볼 것이 없으니 바로 알린다
        byte[] bytes = source.read(FILE);
        if (bytes == null) {
            throw failure(new ContentDiagnostic(FILE, "목차 파일이 없다."));
        }

        // 2) JSON 형식이 올바라야 한다. { } -> Map, [ ] -> List, 숫자/글자
        Object root;
        try {
            root = MAPPER.readValue(new String(bytes, StandardCharsets.UTF_8), Object.class);
        } catch (JacksonException e) {
            throw failure(new ContentDiagnostic(FILE, "JSON 형식이 올바르지 않다: " + e.getMessage()));
        }
        if (!(root instanceof Map<?, ?> object)) {
            throw failure(new ContentDiagnostic(FILE, "{ } 객체여야 한다."));
        }

        // 3~6) 나머지는 문제를 모아서 한 번에 알린다
        List<ContentDiagnostic> diagnostics = new ArrayList<>();
        int formatVersion = readFormatVersion(object.get(FORMAT_VERSION), diagnostics);
        String contentVersion = readContentVersion(object.get(CONTENT_VERSION), diagnostics);
        List<String> files = readFiles(object.get(FILES), diagnostics);

        // files를 읽지 못했으면(목록이 아님) 목록과 실제 파일을 비교할 수 없다
        if (files != null) {
            verifyRequiredFilesAreListed(files, diagnostics);
            verifyListedFilesExist(files, source, diagnostics);
        }

        if (!diagnostics.isEmpty()) {
            throw new ContentLoadException(WHAT, diagnostics);
        }
        // 문제가 없을 때만 만든다(ContentManifest 생성자는 files가 null이면 NPE가 난다)
        return new ContentManifest(formatVersion, contentVersion, files);
    }

    // 규칙 3) formatVersion이 지원하는 값(ContentManifest.CURRENT_FORMAT_VERSION)이다
    private static int readFormatVersion(Object value, List<ContentDiagnostic> into) {
        // JSON 정수는 Integer로 읽힌다. 글자나 소수면 Integer가 아니라서 걸린다
        if (value instanceof Integer number && number == ContentManifest.CURRENT_FORMAT_VERSION) {
            return number;
        }
        into.add(new ContentDiagnostic(FILE + " " + FORMAT_VERSION,
                FORMAT_VERSION + "은 " + ContentManifest.CURRENT_FORMAT_VERSION + "이어야 한다(받은 값: " + value + ")."));
        return 0;
    }

    // 규칙 4) contentVersion이 비어 있지 않은 글자다
    private static String readContentVersion(Object value, List<ContentDiagnostic> into) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        into.add(new ContentDiagnostic(FILE + " " + CONTENT_VERSION, "비어 있지 않은 글자여야 한다."));
        return null;
    }

    // files가 경로(글자)의 목록인지 읽는다. 목록이 아니면 null을 돌려준다
    private static List<String> readFiles(Object value, List<ContentDiagnostic> into) {
        String at = FILE + " " + FILES;
        if (!(value instanceof List<?> items)) {
            into.add(new ContentDiagnostic(at, "[ ] 목록이어야 한다."));
            return null;
        }

        List<String> paths = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof String path && !path.isBlank()) {
                paths.add(path);
            } else {
                into.add(new ContentDiagnostic(at + "[" + i + "]", "비어 있지 않은 파일 경로(글자)여야 한다."));
            }
        }
        return paths;
    }

    // 규칙 5) 로더가 이름으로 읽는 파일이 files 목록에 모두 있다
    private static void verifyRequiredFilesAreListed(List<String> files, List<ContentDiagnostic> into) {
        Set<String> listed = new HashSet<>(files);
        for (String required : REQUIRED_FILES) {
            if (!listed.contains(required)) {
                into.add(new ContentDiagnostic(FILE + " " + FILES,
                        "필요한 파일 '" + required + "'가 목록에 없다."));
            }
        }
    }

    // 규칙 6) files 목록의 파일이 실제로 모두 있다. 없는 파일은 하나도 빠짐없이 알린다
    private static void verifyListedFilesExist(List<String> files, BundleSource source, List<ContentDiagnostic> into) {
        for (String path : files) {
            if (source.read(path) == null) {
                into.add(new ContentDiagnostic(path, "목차에 있는 파일이 없다."));
            }
        }
    }

    private static ContentLoadException failure(ContentDiagnostic diagnostic) {
        return new ContentLoadException(WHAT, List.of(diagnostic));
    }

}
