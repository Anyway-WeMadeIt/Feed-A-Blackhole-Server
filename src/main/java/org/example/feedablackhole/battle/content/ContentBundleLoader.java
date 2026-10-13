package org.example.feedablackhole.battle.content;

import org.example.feedablackhole.content.ContentManifest;
import org.example.feedablackhole.node.content.ContentDiagnostic;
import org.example.feedablackhole.node.content.ContentLoadException;
import org.example.feedablackhole.node.content.NodeContentFiles;
import org.example.feedablackhole.node.content.NodeTree;
import org.example.feedablackhole.node.content.NodeTreeFactory;
import org.example.feedablackhole.node.content.UpgradeStatDefinition;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 콘텐츠 묶음(content/)을 읽어 ContentBundle로 만든다. 지금까지 만든 로더를 순서대로 한 번씩 실행한다.
 *
 * <ol>
 * <li>목차 검사(ContentManifestLoader): 틀리면 파일을 읽을 수 없으니 바로 던진다
 * <li>버전 재계산(ContentVersionVerifier): 틀리면 내용을 믿을 수 없으니 바로 던진다
 * <li>노드 콘텐츠 읽기: 실패해도 멈추지 않고 문제를 모은다
 * <li>전투 콘텐츠 읽기: 실패해도 멈추지 않고 문제를 모은다
 * </ol>
 * 3, 4번의 문제는 한 번에 모아서 ContentLoadException 하나로 알린다(고치는 사람이 한 번에 볼 수 있게).
 * 하나라도 있으면 아무것도 돌려주지 않는다.
 */
public final class ContentBundleLoader {

    // 예외 메시지에 쓰는 이름 ("콘텐츠 묶음을(를) 불러오지 못했다")
    private static final String WHAT = "콘텐츠 묶음";

    // content/ 아래 폴더 경로(목차의 files에 적히는 상대 경로의 앞부분)
    private static final String NODES_DIR = "nodes/";
    private static final String BATTLE_DIR = "battle/";

    private ContentBundleLoader() {
    }

    /**
     * 묶음을 읽는다.
     *
     * @param source 묶음의 파일을 읽는 곳(서버는 클래스패스, 테스트는 메모리)
     * @throws ContentLoadException 목차나 버전이 틀렸거나, 노드·전투 콘텐츠에 문제가 하나라도 있을 때
     */
    public static ContentBundle load(BundleSource source) {
        // 1) 목차: 형식, 필요한 파일이 목록에 있는지, 목록의 파일이 실제로 있는지
        ContentManifest manifest = ContentManifestLoader.load(source);

        // 2) 버전: 목차의 파일을 목차 순서대로 읽어 contentVersion을 다시 계산해 비교
        ContentVersionVerifier.verify(manifest, source);

        // 3, 4번의 문제를 모으는 목록
        List<ContentDiagnostic> diagnostics = new ArrayList<>();

        // 3) 노드 콘텐츠. 실패하면 문제를 모으고 nodeTree는 null로 남는다
        NodeTree nodeTree = readNodeTree(source, diagnostics);

        // 전투 콘텐츠의 statId가 UpgradeStats 시트에 있는지 확인하려면 노드 콘텐츠가 필요하다.
        // 노드를 읽지 못했으면 null을 넘겨서, 규칙 9만 건너뛰고 나머지 규칙은 검사한다
        Set<String> statIds = nodeTree == null ? null : statIdsOf(nodeTree);

        // 4) 전투 콘텐츠. 실패하면 문제를 모으고 battle은 null로 남는다
        BattleContent battle = readBattle(source, statIds, diagnostics);

        // 노드와 전투 중 하나라도 문제가 있으면 하나의 예외로 알린다
        if (!diagnostics.isEmpty()) {
            throw new ContentLoadException(WHAT, diagnostics);
        }

        // 여기까지 왔으면 nodeTree와 battle은 null이 아니다
        return new ContentBundle(manifest, nodeTree, battle);
    }

    // 노드 파일 5개를 글자로 읽어 NodeTree로 만든다. 문제는 into에 모으고, 실패하면 null을 돌려준다
    private static NodeTree readNodeTree(BundleSource source, List<ContentDiagnostic> into) {
        NodeContentFiles files = new NodeContentFiles(
                text(source, NODES_DIR + NodeContentFiles.STATS_FILE),
                text(source, NODES_DIR + NodeContentFiles.NODES_FILE),
                text(source, NODES_DIR + NodeContentFiles.COST_FILE),
                text(source, NODES_DIR + NodeContentFiles.EFFECTS_FILE),
                text(source, NODES_DIR + NodeContentFiles.LAYOUT_FILE));
        try {
            return NodeTreeFactory.load(files);
        } catch (ContentLoadException e) {
            into.addAll(e.getDiagnostics()); // 노드 쪽 문제를 그대로 옮겨 담는다
            return null;
        }
    }

    // battle.json을 글자로 읽어 BattleContent로 만든다. 문제는 into에 모으고, 실패하면 null을 돌려준다
    private static BattleContent readBattle(BundleSource source, Set<String> statIds, List<ContentDiagnostic> into) {
        String json = text(source, BATTLE_DIR + BattleContentLoader.FILE);
        try {
            return BattleContentLoader.load(json, statIds);
        } catch (ContentLoadException e) {
            into.addAll(e.getDiagnostics()); // 전투 쪽 문제를 그대로 옮겨 담는다
            return null;
        }
    }

    // UpgradeStats 시트의 StatId를 모두 모은다(전투 콘텐츠의 규칙 9가 쓴다)
    private static Set<String> statIdsOf(NodeTree nodeTree) {
        Set<String> statIds = new HashSet<>();
        for (UpgradeStatDefinition stat : nodeTree.content().stats()) {
            statIds.add(stat.statId());
        }
        return statIds;
    }

    // 파일 바이트를 UTF-8 글자로 바꾼다. 없는 파일은 null이다(목차 검사가 있음을 이미 확인했다)
    private static String text(BundleSource source, String path) {
        byte[] bytes = source.read(path);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

}
