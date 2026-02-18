package com.proovy.global.infra.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiClient {

    private final WebClient webClient;

    @Value("${openrouter.api.key}")
    private String apiKey;

    @Value("${openrouter.api.model:google/gemini-2.5-flash-lite-preview-09-2025}")
    private String model;

    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";

    private static final String TITLE_PROMPT_TEMPLATE = """
            다음 사용자 질문을 읽고, 노트 제목을 30자 이내로 만들어주세요.

            규칙:
            - 30자 이내
            - 질문의 핵심 주제를 간결하게 표현
            - 제목만 출력 (설명, 따옴표, 줄바꿈 없이)

            질문: %s
            """;

    /**
     * 사용자 질문을 기반으로 노트 제목을 생성합니다. (OpenRouter 경유)
     *
     * @param questionText 사용자의 첫 질문 텍스트
     * @return 생성된 노트 제목 (30자 이내)
     */
    public String generateNoteTitle(String questionText) {
        // 질문이 너무 길면 앞부분만 사용 (토큰 절약)
        String truncatedText = questionText.length() > 500
                ? questionText.substring(0, 500)
                : questionText;

        String prompt = String.format(TITLE_PROMPT_TEMPLATE, truncatedText);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                ),
                "max_tokens", 60,
                "temperature", 0.2
        );

        log.debug("OpenRouter 제목 생성 요청 - model: {}", model);

        OpenRouterResponse response = webClient.post()
                .uri(OPENROUTER_URL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(OpenRouterResponse.class)
                .block();

        if (response == null
                || response.choices() == null
                || response.choices().isEmpty()
                || response.choices().get(0).message() == null) {
            throw new RuntimeException("OpenRouter API 응답이 비어있습니다.");
        }

        String title = response.choices().get(0).message().content().trim();

        // 30자 초과 시 자르기
        if (title.length() > 30) {
            title = title.substring(0, 30);
        }

        log.debug("OpenRouter 제목 생성 완료: {}", title);
        return title;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenRouterResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            String role,
            String content,
            @JsonProperty("refusal") String refusal
    ) {}
}
