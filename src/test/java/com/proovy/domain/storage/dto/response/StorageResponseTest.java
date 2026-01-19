package com.proovy.domain.storage.dto.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class StorageResponseTest {

    @Test
    @DisplayName("StorageResponse가 올바르게 생성된다")
    void createStorageResponse() {
        // given & when
        StorageResponse response = StorageResponse.of(
                430,
                3000,
                "free",
                true,
                List.of()
        );

        // then
        assertThat(response.totalUsed()).isEqualTo(430);
        assertThat(response.totalLimit()).isEqualTo(3000);
        assertThat(response.plan().planType()).isEqualTo("free");
        assertThat(response.plan().isActive()).isTrue();
        assertThat(response.notes()).isEmpty();
    }

    @ParameterizedTest
    @DisplayName("용량이 올바른 형식으로 표시된다")
    @CsvSource({
            "0, 3000, 0MB, 3GB",
            "430, 3000, 430MB, 3GB",
            "1000, 3000, 1GB, 3GB",
            "1500, 3000, 1.50GB, 3GB",
            "2500, 100000, 2.50GB, 100GB"
    })
    void formatStorage(int used, int limit, String expectedUsed, String expectedLimit) {
        // when
        StorageResponse response = StorageResponse.of(used, limit, "free", true, List.of());

        // then
        assertThat(response.totalUsedDisplay()).isEqualTo(expectedUsed);
        assertThat(response.totalLimitDisplay()).isEqualTo(expectedLimit);
    }

    @ParameterizedTest
    @DisplayName("사용률이 올바르게 계산된다")
    @CsvSource({
            "0, 3000, 0",
            "300, 3000, 10",
            "1500, 3000, 50",
            "2700, 3000, 90",
            "3000, 3000, 100"
    })
    void calculateUsagePercent(int used, int limit, int expectedPercent) {
        // when
        StorageResponse response = StorageResponse.of(used, limit, "free", true, List.of());

        // then
        assertThat(response.usagePercent()).isEqualTo(expectedPercent);
    }

    @Test
    @DisplayName("limit이 0이면 usagePercent는 0이다")
    void zeroLimitReturnsZeroPercent() {
        // when
        StorageResponse response = StorageResponse.of(100, 0, "free", true, List.of());

        // then
        assertThat(response.usagePercent()).isEqualTo(0);
    }
}
