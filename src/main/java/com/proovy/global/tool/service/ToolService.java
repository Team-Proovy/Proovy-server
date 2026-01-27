package com.proovy.global.tool.service;

import com.proovy.global.tool.entity.Tool;
import com.proovy.global.tool.repository.ToolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ToolService {

    private final ToolRepository toolRepository;

    /**
     * 도구 목록 조회 (검색어 지원)
     */
    public List<Tool> getToolList(String query) {
        log.debug("도구 목록 조회 요청 - query: {}", query);

        if (query != null && !query.trim().isEmpty()) {
            // 검색어가 있으면 이름으로 검색
            return toolRepository.searchByNameQuery(query.trim());
        } else {
            // 검색어가 없으면 전체 활성화된 도구 조회
            return toolRepository.findByIsActiveTrueOrderByDisplayOrder();
        }
    }
}

