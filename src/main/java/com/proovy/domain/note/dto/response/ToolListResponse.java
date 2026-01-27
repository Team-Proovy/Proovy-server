package com.proovy.domain.note.dto.response;

import com.proovy.global.tool.entity.Tool;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

@Getter
@Builder
@Schema(description = "도구 목록 조회 응답")
public class ToolListResponse {

    @Schema(description = "도구 목록")
    private List<ToolInfo> tools;

    @Getter
    @Builder
    @Schema(description = "도구 정보")
    public static class ToolInfo {
        @Schema(description = "도구 고유 ID", example = "1")
        private Long toolId;

        @Schema(description = "도구 식별 코드", example = "GRAPH")
        private String toolCode;

        @Schema(description = "도구 표시 이름", example = "그래프 그리기")
        private String name;

        @Schema(description = "도구 기능 설명", example = "입력한 수식을 바탕으로 시각적인 함수 그래프를 생성합니다.")
        private String description;

        @Schema(description = "UI 아이콘 타입", example = "chart_line")
        private String iconType;

        @Schema(description = "도구 활성화 여부", example = "true")
        private Boolean isActive;

        @Schema(description = "드롭다운 표시 순서", example = "1")
        private Integer displayOrder;

        public static ToolInfo from(Tool tool) {
            return ToolInfo.builder()
                    .toolId(tool.getId())
                    .toolCode(tool.getToolCode())
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .iconType(tool.getIconType())
                    .isActive(tool.getIsActive())
                    .displayOrder(tool.getDisplayOrder())
                    .build();
        }
    }

    public static ToolListResponse from(List<Tool> tools) {
        List<ToolInfo> toolInfos = tools.stream()
                .map(ToolInfo::from)
                .collect(Collectors.toList());

        return ToolListResponse.builder()
                .tools(toolInfos)
                .build();
    }
}
