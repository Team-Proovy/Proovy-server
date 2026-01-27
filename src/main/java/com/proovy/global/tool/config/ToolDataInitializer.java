package com.proovy.global.tool.config;

import com.proovy.global.tool.entity.Tool;
import com.proovy.global.tool.repository.ToolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolDataInitializer implements CommandLineRunner {

    private final ToolRepository toolRepository;

    @Override
    @Transactional
    public void run(String... args) {
        // 이미 데이터가 있으면 초기화하지 않음
        if (toolRepository.count() > 0) {
            log.info("도구 데이터가 이미 존재합니다. 초기화를 건너뜁니다.");
            return;
        }

        log.info("도구 초기 데이터를 생성합니다...");

        List<Tool> tools = List.of(
                Tool.builder()
                        .toolCode("GRAPH")
                        .name("그래프 그리기")
                        .description("입력한 수식을 바탕으로 시각적인 함수 그래프를 생성합니다.")
                        .iconType("chart_line")
                        .isActive(true)
                        .displayOrder(1)
                        .build(),
                Tool.builder()
                        .toolCode("SOLUTION")
                        .name("해설지 생성하기")
                        .description("문제에 대한 단계별 풀이 과정과 정답 해설지를 생성합니다.")
                        .iconType("file_text")
                        .isActive(true)
                        .displayOrder(2)
                        .build(),
                Tool.builder()
                        .toolCode("VARIATION")
                        .name("변형 문제 생성하기")
                        .description("기존 문제를 바탕으로 유사한 유형의 변형 문제를 생성합니다.")
                        .iconType("copy_plus")
                        .isActive(true)
                        .displayOrder(3)
                        .build()
        );

        toolRepository.saveAll(tools);
        log.info("도구 초기 데이터 생성 완료: {} 개", tools.size());
    }
}

