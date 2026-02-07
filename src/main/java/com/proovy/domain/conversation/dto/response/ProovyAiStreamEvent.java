package com.proovy.domain.conversation.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Proovy-ai SSE 스트림 이벤트 DTO
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProovyAiStreamEvent {

    @JsonProperty("event")
    private String event;

    @JsonProperty("data")
    private Map<String, Object> data;
}
