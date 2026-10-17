package com.proovy.domain.run.contract;

/**
 * 사용자가 보고 있던 Note asset page. selection이 null이면 page 전체다.
 *
 * @param page 1-based
 */
public record ViewerFocus(String noteAssetId, Integer page, PageSelection selection) {
}
