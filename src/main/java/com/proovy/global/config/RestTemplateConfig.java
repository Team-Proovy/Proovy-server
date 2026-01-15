package com.proovy.global.config;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();

        // 기본 메시지 컨버터를 유지하면서 필요한 컨버터 추가
        List<HttpMessageConverter<?>> messageConverters = restTemplate.getMessageConverters();

        // JSON 처리를 위한 컨버터 추가
        messageConverters.add(0, new MappingJackson2HttpMessageConverter());

        // UTF-8 문자열 처리를 위한 컨버터 추가
        messageConverters.add(1, new StringHttpMessageConverter(StandardCharsets.UTF_8));

        // Form 데이터 처리를 위한 컨버터 추가
        messageConverters.add(2, new FormHttpMessageConverter());

        return restTemplate;
    }
}