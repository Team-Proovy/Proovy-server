package com.proovy.global.tool.repository;

import com.proovy.global.tool.entity.Tool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ToolRepository extends JpaRepository<Tool, Long> {

    /**
     * 활성화된 도구 목록 조회 (표시 순서대로)
     */
    List<Tool> findByIsActiveTrueOrderByDisplayOrder();

    /**
     * 도구 이름으로 검색 (자동완성용)
     */
    @Query("SELECT t FROM Tool t WHERE t.isActive = true " +
           "AND LOWER(t.name) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "ORDER BY t.displayOrder")
    List<Tool> searchByNameQuery(@Param("query") String query);
}

