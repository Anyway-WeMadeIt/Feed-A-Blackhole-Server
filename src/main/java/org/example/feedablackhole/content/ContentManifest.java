package org.example.feedablackhole.content;

import java.util.List;

/**
 * 콘텐츠 묶음의 목차(content/manifest.json). 형식은 docs/server-sync-contracts.md 1.3.
 *
 * @param formatVersion  묶음 형식의 버전(현재 1)
 * @param contentVersion 내보내기 도구가 계산한 콘텐츠 버전. 서버는 시작할 때 다시 계산해 이 값과 비교한다
 * @param files          묶음의 파일(content/ 기준 상대 경로, 구분자 /). 이 순서로 버전을 계산한다
 */
public record ContentManifest(int formatVersion, String contentVersion, List<String> files) {

    public static final String FILE = "manifest.json";
    public static final int CURRENT_FORMAT_VERSION = 1;

    public ContentManifest {
        files = List.copyOf(files);
    }

}
