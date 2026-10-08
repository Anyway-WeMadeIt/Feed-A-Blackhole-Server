package org.example.feedablackhole.node.content;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 콘텐츠를 불러오지 못했다. 찾은 문제를 한 번에 모두 담는다(하나만 고치고 다시 돌리는 일을 줄이려고).
 */
public class ContentLoadException extends RuntimeException {

    private final List<ContentDiagnostic> diagnostics;

    public ContentLoadException(String what, List<ContentDiagnostic> diagnostics) {
        super(what + "을(를) 불러오지 못했다. 문제 " + diagnostics.size() + "개:\n"
                + diagnostics.stream().map(diagnostic -> "  - " + diagnostic).collect(Collectors.joining("\n")));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<ContentDiagnostic> getDiagnostics() {
        return diagnostics;
    }

}
