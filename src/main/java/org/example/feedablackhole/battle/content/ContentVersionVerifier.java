package org.example.feedablackhole.battle.content;

import org.example.feedablackhole.content.ContentManifest;
import org.example.feedablackhole.content.ContentVersion;
import org.example.feedablackhole.content.ContentVersion.ContentFile;
import org.example.feedablackhole.node.content.ContentDiagnostic;
import org.example.feedablackhole.node.content.ContentLoadException;

import java.util.ArrayList;
import java.util.List;

/**
 * 목차의 contentVersion이 실제 파일 바이트로 계산한 값과 같은지 확인한다(계약 1.3).
 * 파일을 손으로 고쳤거나 목차를 다시 내보내지 않은 묶음을 서버 시작 단계에서 걸러내려는 것이다.
 */
public final class ContentVersionVerifier {

    // 예외 메시지에 쓰는 이름 ("콘텐츠 버전을(를) 불러오지 못했다")
    private static final String WHAT = "콘텐츠 버전";

    private ContentVersionVerifier() {
    }

    /**
     * 목차의 파일을 목차의 순서대로 읽어 버전을 다시 계산하고, 목차에 적힌 버전과 비교한다.
     *
     * @param manifest 목차(파일 목록과 contentVersion)
     * @param source   묶음의 파일을 읽는 곳
     * @throws ContentLoadException 읽지 못한 파일이 있거나, 계산한 버전이 목차의 버전과 다를 때
     */
    public static void verify(ContentManifest manifest, BundleSource source) {
        List<ContentFile> contentFiles = new ArrayList<>(); // 버전 계산에 쓸 (경로, 바이트) 목록
        List<ContentDiagnostic> diagnostics = new ArrayList<>(); // 읽지 못한 파일을 모으는 목록

        // 목차의 순서 그대로 읽는다. 버전은 "경로 + 바이트"를 이 순서대로 이어 붙여 계산하므로
        // 순서를 바꾸면(정렬, Set 등) 계산한 값이 달라진다. 그래서 향상된 for문으로 앞에서부터 돈다
        for (String path : manifest.files()) {
            byte[] bytes = source.read(path); // 파일이 없으면 예외가 아니라 null이 돌아온다

            if (bytes == null) {
                // 읽지 못한 파일은 하나도 빠짐없이 모아서 알린다
                diagnostics.add(new ContentDiagnostic(path, "목차에 있는 파일이 없다."));
            } else {
                contentFiles.add(new ContentFile(path, bytes));
            }
        }

        // 읽지 못한 파일이 있으면 해시가 의미가 없으니, 계산하지 않고 여기서 끝낸다
        if (!diagnostics.isEmpty()) {
            throw new ContentLoadException(WHAT, diagnostics);
        }

        // 파일 바이트로 버전을 다시 계산해 목차에 적힌 값과 비교한다. 문자열은 == 가 아니라 equals로 비교한다
        String computed = ContentVersion.of(contentFiles);
        if (!computed.equals(manifest.contentVersion())) {
            // 목차의 값과 계산한 값을 둘 다 적어서, 어느 쪽이 틀렸는지 바로 보이게 한다
            throw new ContentLoadException(WHAT, List.of(new ContentDiagnostic(
                    ContentManifest.FILE + " contentVersion",
                    "contentVersion이 파일 내용과 맞지 않다. 목차: " + manifest.contentVersion()
                            + ", 파일로 계산한 값: " + computed + ".")));
        }
    }
}
