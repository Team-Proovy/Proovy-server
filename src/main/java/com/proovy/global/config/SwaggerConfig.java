package com.proovy.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;


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

    @Bean
    public RouterFunction<ServerResponse> customSwaggerInitializer() {
        return RouterFunctions.route()
                .GET("/swagger-ui/swagger-initializer.js", request ->
                        ServerResponse.ok()
                                .contentType(MediaType.valueOf("text/javascript"))
                                .body("""
                                        window.onload = function() {
                                          window.ui = SwaggerUIBundle({
                                            configUrl: '/v3/api-docs/swagger-config',
                                            dom_id: '#swagger-ui',
                                            deepLinking: true,
                                            presets: [
                                              SwaggerUIBundle.presets.apis,
                                              SwaggerUIStandalonePreset
                                            ],
                                            plugins: [
                                              SwaggerUIBundle.plugins.DownloadUrl
                                            ],
                                            layout: "StandaloneLayout",
                                            operationsSorter: function(a, b) {
                                              var aId = a.get("operation").get("operationId") || "";
                                              var bId = b.get("operation").get("operationId") || "";
                                              return aId.localeCompare(bId);
                                            }
                                          });
                                        };
                                        """))
                .build();
    }
}
