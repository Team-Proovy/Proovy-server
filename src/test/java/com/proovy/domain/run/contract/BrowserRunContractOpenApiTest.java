package com.proovy.domain.run.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BrowserRunContractOpenApiTest {

    @Test
    @DisplayName("계약 DTO가 경로 없이 component schema로 등록되고 enum은 wire 값으로 표기된다")
    void registersComponentSchemas() {
        OpenAPI openApi = new OpenAPI();
        new BrowserRunContractOpenApi().browserRunContractSchemas().customise(openApi);

        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        assertThat(schemas).containsKeys(
                "ConversationRunRequest", "ViewerFocus", "PageSelection", "ActiveRunConflict", "RunStatus",
                "RunFailureSummary", "RunCancelResult", "ConversationPage", "ConversationMessage",
                "BrowserRunFrame", "RunCreatedPayload", "RunCanceledPayload");
        assertThat(openApi.getPaths()).isNull();
        Schema<?> request = schemas.get("ConversationRunRequest");
        assertThat(request.getProperties()).containsOnlyKeys(
                "requestId", "noteId", "text", "latex", "requestedTasks", "requestedOutputs",
                "turnAttachmentIds", "viewerFocus");
        Schema<?> tasks = (Schema<?>) request.getProperties().get("requestedTasks");
        assertThat(enumValues(tasks.getItems())).containsExactly("solve", "check", "explain");
        Schema<?> cancel = schemas.get("RunCancelResult");
        assertThat(enumValues((Schema<?>) cancel.getProperties().get("outcome")))
                .containsExactly("accepted", "already_canceling", "already_terminal");
    }

    private static List<String> enumValues(Schema<?> schema) {
        return schema.getEnum().stream().map(String::valueOf).toList();
    }
}
