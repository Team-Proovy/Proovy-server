package com.proovy.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    private static final String BEARER_TOKEN_PREFIX = "Bearer";

    @Bean
    public OpenAPI openAPI() {
        String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("Proovy API")
                        .description("Proovy 백엔드 API 명세서")
                        .version("v1.0.0"))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("로컬 개발 서버"),
                        new Server()
                                .url("https://api.proovy.com")
                                .description("프로덕션 서버")
                ))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT 액세스 토큰을 입력하세요 (Bearer 접두사 없이)")));
    }

    @Bean
    public OpenApiCustomizer sortOperationsByOperationId() {
        return openApi -> {
            Paths paths = openApi.getPaths();
            if (paths != null) {
                Paths sortedPaths = new Paths();
                paths.entrySet().stream()
                        .sorted((e1, e2) -> {
                            String opId1 = getOperationId(e1.getValue());
                            String opId2 = getOperationId(e2.getValue());
                            return opId1.compareTo(opId2);
                        })
                        .forEach(e -> sortedPaths.addPathItem(e.getKey(), e.getValue()));
                openApi.setPaths(sortedPaths);
            }
        };
    }

    private String getOperationId(PathItem pathItem) {
        return pathItem.readOperations().stream()
                .map(op -> op.getOperationId())
                .filter(id -> id != null)
                .findFirst()
                .orElse("");
    }
}
