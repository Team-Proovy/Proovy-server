package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunFixtures.RAW;
import static com.proovy.domain.run.brain.InternalRunFixtures.load;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.run.contract.CanonicalJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** canonical.json의 number·string·document·정규화 사례를 Java serializer가 byte 단위로 재현한다. */
class InternalRunCanonicalJsonTest {

    private static final JsonNode RULES = load("canonical.json");

    @Test
    @DisplayName("number·string·document 사례의 canonical 출력이 Python과 같다")
    void casesMatch() {
        int checked = 0;
        for (String section : new String[]{"number_cases", "string_cases", "document_cases"}) {
            for (JsonNode example : RULES.get(section)) {
                Object input = RAW.convertValue(example.get("input"), Object.class);
                assertThat(CanonicalJson.write(input)).as(section + " " + example.get("input"))
                        .isEqualTo(example.get("canonical").asText());
                checked++;
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(15);
    }

    @Test
    @DisplayName("문자열 정규화는 Python str.strip() 집합으로 양끝을 지운다(Java String.strip()보다 넓다)")
    void normalizationCases() {
        for (JsonNode example : RULES.get("normalization_cases")) {
            assertThat(InternalRunContract.strip(example.get("wire").asText()))
                    .isEqualTo(example.get("normalized").asText());
        }
        assertThat(" x".strip()).as("Java strip()은 U+00A0을 남긴다").isNotEqualTo("x");
    }

    @Test
    @DisplayName("버전 표식이 같다")
    void version() {
        assertThat(RULES.get("version").asText()).isEqualTo("canonical-json-v1");
    }
}
