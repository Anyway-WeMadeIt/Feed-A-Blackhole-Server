package org.example.feedablackhole;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

// 브랜치 보호 검증용으로 일부러 실패시키는 테스트다. 이 브랜치는 병합하지 않고 버린다.
class BranchProtectionProbeTest {

    @Test
    void failsOnPurpose() {
        fail("branch protection probe");
    }

}
