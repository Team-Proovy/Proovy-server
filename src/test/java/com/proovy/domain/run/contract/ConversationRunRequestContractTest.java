package com.proovy.domain.run.contract;

import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.MAPPER;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.entries;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.schemaOk;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.global.response.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** requests/·fingerprint/ fixture를 Java 판정·DTO·fingerprint로 재현한다. */
class ConversationRunRequestContractTest {

    @Test
    @DisplayName("정상 요청은 접수되고 DTO로 바인딩되며 fingerprint가 fixture와 같다")
    void validRequestsAreAccepted() throws Exception {
        List<Map.Entry<String, JsonNode>> cases = entries("requests/valid");
        assertThat(cases).hasSizeGreaterThanOrEqualTo(10);
        for (Map.Entry<String, JsonNode> entry : cases) {
            JsonNode request = entry.getValue().get("request");
            assertThat(ConversationRunRequestValidator.validate(request)).as(entry.getKey()).isEmpty();
            assertThat(schemaOk("ConversationRunRequest", request)).as(entry.getKey()).isTrue();
            BrowserRequestFingerprint fingerprint = BrowserRequestFingerprint.of(bind(request));
            assertThat(fingerprint.canonical()).as(entry.getKey())
                    .isEqualTo(entry.getValue().get("expected_canonical").asText());
            assertThat(fingerprint.sha256()).as(entry.getKey())
                    .isEqualTo(entry.getValue().get("expected_fingerprint").asText());
        }
    }

    @Test
    @DisplayName("거부 요청은 기대 code로 거부되고 schema 판정도 fixture와 같다")
    void invalidRequestsAreRejected() {
        List<Map.Entry<String, JsonNode>> cases = entries("requests/invalid");
        assertThat(cases).hasSizeGreaterThanOrEqualTo(40);
        for (Map.Entry<String, JsonNode> entry : cases) {
            JsonNode fixture = entry.getValue();
            JsonNode request = fixture.get("request");
            ErrorCode expected = ErrorCode.valueOf(fixture.at("/expected/code").asText());
            assertThat(expected.getHttpStatus().value()).isEqualTo(fixture.at("/expected/http").asInt());
            assertThat(ConversationRunRequestValidator.validate(request)).as(entry.getKey())
                    .isEqualTo(Optional.of(expected));
            // Java 정규식 엔진도 Python과 같은 schema 판정을 내려야 한다(끝 줄바꿈·seq/noteId 상한 등).
            assertThat(schemaOk("ConversationRunRequest", request)).as(entry.getKey())
                    .isEqualTo(!fixture.get("schema_rejects").asBoolean());
        }
    }

    @Test
    @DisplayName("fingerprint 쌍의 same/different 관계와 canonical이 fixture와 같다")
    void fingerprintRelations() throws Exception {
        List<Map.Entry<String, JsonNode>> cases = entries("fingerprint");
        assertThat(cases).hasSizeGreaterThanOrEqualTo(16);
        for (Map.Entry<String, JsonNode> entry : cases) {
            JsonNode fixture = entry.getValue();
            BrowserRequestFingerprint left = BrowserRequestFingerprint.of(bind(fixture.get("left")));
            BrowserRequestFingerprint right = BrowserRequestFingerprint.of(bind(fixture.get("right")));
            assertThat(left.canonical()).as(entry.getKey())
                    .isEqualTo(fixture.get("expected_canonical_left").asText());
            assertThat(right.canonical()).as(entry.getKey())
                    .isEqualTo(fixture.get("expected_canonical_right").asText());
            assertThat(left.sha256()).isEqualTo(fixture.get("expected_fingerprint_left").asText());
            assertThat(right.sha256()).isEqualTo(fixture.get("expected_fingerprint_right").asText());
            assertThat(left.equals(right)).as(entry.getKey())
                    .isEqualTo("same".equals(fixture.get("relation").asText()));
        }
    }

    @ParameterizedTest
    @DisplayName("canonical 실수 표기는 Brain 규칙(최단 round-trip, 지수 없음, 정수값은 정수)과 같다")
    @CsvSource({
            "0.1, 0.1",
            "0.5, 0.5",
            "1.0, 1",
            "-0.0, 0",
            "1e-5, 0.00001",
            "1.5e-7, 0.00000015",
            "123456789.5, 123456789.5",
            "1e16, 10000000000000000",
            "0.30000000000000004, 0.30000000000000004"
    })
    void canonicalNumbers(double value, String expected) {
        assertThat(CanonicalJson.number(value)).isEqualTo(expected);
    }

    @Test
    @DisplayName("canonical 문자열은 제어문자·짝 없는 surrogate만 escape하고 나머지는 그대로 쓴다")
    void canonicalStrings() {
        assertThat(CanonicalJson.write(Map.of("b", "가\u0001\"\\\n𝑥", "a", List.of(1, "\ud800"))))
                .isEqualTo("{\"a\":[1,\"\\ud800\"],\"b\":\"가\\u0001\\\"\\\\\\n𝑥\"}");
    }

    @Test
    @DisplayName("strip 집합은 Python str.strip()과 같다 — Java String.strip()이 남기는 공백도 제거한다")
    void stripSet() {
        String blanks = "\u00a0\u0085\u202f\u2007\u3000\u001c \t";
        assertThat(BrowserRunContract.strip(blanks + "가 나" + blanks)).isEqualTo("가 나");
        assertThat(("\u00a0x\u202f").strip()).isNotEqualTo("x");
        assertThat(BrowserRunContract.strip("\u200bx")).isEqualTo("\u200bx");
    }

    private static ConversationRunRequest bind(JsonNode request) throws Exception {
        return MAPPER.treeToValue(request, ConversationRunRequest.class);
    }
}
