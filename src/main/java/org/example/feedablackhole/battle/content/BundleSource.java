package org.example.feedablackhole.battle.content;

/**
 * 콘텐츠 묶음의 파일을 읽는 곳.
 * 서버는 클래스패스(content/)에서, 테스트는 메모리나 테스트 리소스에서 읽는다.
 */
@FunctionalInterface
public interface BundleSource {

    /**
     * @param path content/ 기준 상대 경로(구분자 /). 예: "manifest.json", "battle/battle.json"
     * @return 파일의 바이트. 파일이 없으면 null
     */
    byte[] read(String path);

}
