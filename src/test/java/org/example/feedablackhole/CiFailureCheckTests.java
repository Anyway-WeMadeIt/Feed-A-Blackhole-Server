package org.example.feedablackhole;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * CI가 실패를 잡고 리포트를 올리는지 확인하는 임시 테스트. 확인한 뒤 이 파일을 지운다.
 */
class CiFailureCheckTests {

    @Test
    void failsOnPurpose() {
        assertThat(1).isEqualTo(2);
    }

}
