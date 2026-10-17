package com.proovy.domain.run.contract;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.properties.SpringDocConfigProperties;
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

    /** springdoc이 내보내는 OpenAPI 버전(기본 3.1)에 맞는 converter로 만든다 — 3.0 schema는 3.1 출력에서 type을 잃는다. */
    @Bean
    public OpenApiCustomizer browserRunContractSchemas(SpringDocConfigProperties springDoc) {
        boolean openapi31 = springDoc.isOpenapi31();
        return openApi -> addSchemas(openApi, openapi31);
    }

    static void addSchemas(OpenAPI openApi, boolean openapi31) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        Components components = openApi.getComponents();
        for (Class<?> type : SCHEMA_TYPES) {
            ModelConverters.getInstance(openapi31).readAll(new AnnotatedType(type)).forEach(components::addSchemas);
        }
    }
}
