package com.proovy.domain.run.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BrowserRunContractOpenApiTest {

    @ParameterizedTest(name = "openapi31={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("직렬화된 OpenAPI 문서에 계약 DTO가 경로 없이 type·wire enum과 함께 component schema로 나온다")
    void registersComponentSchemas(boolean openapi31) throws Exception {
        OpenAPI openApi = new OpenAPI(openapi31 ? SpecVersion.V31 : SpecVersion.V30);
        BrowserRunContractOpenApi.addSchemas(openApi, openapi31);
        ObjectMapper writer = openapi31 ? Json31.mapper() : Json.mapper();
        JsonNode document = writer.readTree(writer.writeValueAsString(openApi));

        assertThat(document.has("paths")).isFalse();
        JsonNode schemas = document.at("/components/schemas");
        for (String name : List.of(
                "ConversationRunRequest", "ViewerFocus", "PageSelection", "ActiveRunConflict", "RunStatus",
                "RunFailureSummary", "RunCancelResult", "ConversationPage", "ConversationMessage",
                "BrowserRunFrame", "RunCreatedPayload", "RunCanceledPayload")) {
            assertThat(typeOf(schemas.get(name))).as(name).isEqualTo("object");
        }
        JsonNode request = schemas.at("/ConversationRunRequest/properties");
        assertThat(fieldNames(request)).containsExactlyInAnyOrder(
                "requestId", "noteId", "text", "latex", "requestedTasks", "requestedOutputs",
                "turnAttachmentIds", "viewerFocus");
        assertThat(typeOf(request.get("text"))).isEqualTo("string");
        assertThat(texts(request.at("/requestedTasks/items/enum"))).containsExactly("solve", "check", "explain");
        assertThat(texts(schemas.at("/RunCancelResult/properties/outcome/enum")))
                .containsExactly("accepted", "already_canceling", "already_terminal");
    }

    /** 3.0은 {@code type: "object"}, 3.1은 {@code type: "object"} 또는 {@code ["object"]}. */
    private static String typeOf(JsonNode schema) {
        JsonNode type = schema.get("type");
        return type == null ? null : type.isArray() ? type.get(0).asText() : type.asText();
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(java.util.Map.Entry::getKey).toList();
    }

    private static List<String> texts(JsonNode array) {
        return array.valueStream().map(JsonNode::asText).toList();
    }
}
