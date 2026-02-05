package com.proovy.domain.credit.service;

import com.proovy.domain.credit.dto.response.CreditCostResponse;
import com.proovy.domain.credit.entity.CreditEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreditCostService {

    // TODO: DB에서 관리하도록 변경
    private static final int OCR_COST = 10;
    private static final int CODE_EXECUTION_COST = 5;

    /**
     * 크레딧 비용 정책 조회
     * SPEND 타입 이벤트만 반환
     */
    public CreditCostResponse getCreditCosts() {
        List<CreditCostResponse.CreditCostItemDto> costs = new ArrayList<>();

        // OCR 비용
        costs.add(CreditCostResponse.CreditCostItemDto.builder()
                .eventType(CreditEventType.OCR.name())
                .description(CreditEventType.OCR.getDescription())
                .costAmount(OCR_COST)
                .isFixed(true)
                .build());

        // 코드 실행 비용
        costs.add(CreditCostResponse.CreditCostItemDto.builder()
                .eventType(CreditEventType.CODE_EXECUTION.name())
                .description(CreditEventType.CODE_EXECUTION.getDescription())
                .costAmount(CODE_EXECUTION_COST)
                .isFixed(true)
                .build());

        // LLM 질의 비용 (사용량 기반)
        costs.add(CreditCostResponse.CreditCostItemDto.builder()
                .eventType(CreditEventType.LLM_QUERY.name())
                .description(CreditEventType.LLM_QUERY.getDescription() + " (사용량 기반)")
                .costAmount(null)
                .isFixed(false)
                .build());

        log.info("크레딧 비용 정책 조회 완료, 총 {}개 항목", costs.size());
        return CreditCostResponse.builder()
                .costs(costs)
                .build();
    }
}
