package com.proovy.domain.run.contract;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * browser-run-v1 DTO를 OpenAPI component schema로 등록한다. 아직 controller가 없으므로
 * 경로는 만들지 않는다(실제 처리는 후속 태스크). 판정 기준은 OpenAPI가 아니라
 * proovy-brain browser-run-v1 schema·fixture다.
 */
@Configuration
public class BrowserRunContractOpenApi {

    static final List<Class<?>> SCHEMA_TYPES = List.of(
            ConversationRunRequest.class,
            ActiveRunConflict.class,
            RunStatus.class,
            RunCancelResult.class,
            ConversationPage.class,
            BrowserRunFrame.class,
            RunCreatedPayload.class,
            ResponsePageStartedPayload.class,
            ResponsePageCompletedPayload.class,
            RunCancelingPayload.class,
            RunCanceledPayload.class);

    @Bean
    public OpenApiCustomizer browserRunContractSchemas() {
        return BrowserRunContractOpenApi::addSchemas;
    }

    static void addSchemas(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        Components components = openApi.getComponents();
        for (Class<?> type : SCHEMA_TYPES) {
            ModelConverters.getInstance().readAll(new AnnotatedType(type)).forEach(components::addSchemas);
        }
    }
}
