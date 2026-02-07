package com.proovy.domain.conversation.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Proovy-ai 에이전트에 전달할 요청 DTO
 */
@Getter
@Builder
public class ProovyAiRequest {

    // StreamInput(UserInput)와 동일한 필드 구조를 사용해야 한다.

    // 필수: message (이미 lowerCamelCase)
    @JsonProperty("message")
    private String message;

    // 선택: threadId (없으면 Proovy-ai에서 새 thread 생성)
    @JsonProperty("threadId")
    private String threadId;

    // 선택: userId (없으면 Proovy-ai에서 새 user 생성)
    @JsonProperty("userId")
    private String userId;

    // 선택: filesUrl
    @JsonProperty("filesUrl")
    private List<String> filesUrl;

    // 선택: chosenFeatures
    @JsonProperty("chosenFeatures")
    private List<String> chosenFeatures;

    // 선택: streamTokens (기본 True이지만 명시적으로 보낼 수 있음)
    @JsonProperty("streamTokens")
    private Boolean streamTokens;

    // 선택: agentConfig - 기존 metadata를 이 필드로 보낸다.
    @JsonProperty("agentConfig")
    private Map<String, Object> agentConfig;
}
