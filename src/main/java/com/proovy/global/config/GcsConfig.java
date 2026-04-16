package com.proovy.global.config;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class GcsConfig {

    @Value("${gcs.project-id}")
    private String projectId;

    @Value("${gcs.credentials-json:}")
    private String credentialsJson;

    @Bean
    public Storage gcsStorage() throws IOException {
        StorageOptions.Builder builder = StorageOptions.newBuilder()
                .setProjectId(projectId);

        if (credentialsJson != null && !credentialsJson.isBlank()) {
            ServiceAccountCredentials credentials = ServiceAccountCredentials
                    .fromStream(new ByteArrayInputStream(
                            credentialsJson.getBytes(StandardCharsets.UTF_8)));
            builder.setCredentials(credentials);
        }
        // credentialsJson 미설정 시 ADC(Application Default Credentials) 사용
        // Cloud Run에서는 서비스 계정이 자동 적용됨

        return builder.build().getService();
    }
}
