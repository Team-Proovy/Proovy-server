package com.proovy.domain.run.snapshot;

import java.util.Map;

/**
 * 한 번의 전달에만 쓰는 실행 자격증명(Asset Access Grant, read URL). snapshot에 저장하지 않고 fingerprint에도 들어가지 않는다.
 * 전달마다 새로 발급해 넘긴다(S09/S10). {@link #toString()}은 값을 쓰지 않는다.
 *
 * @param assetAccessGrant       Note Corpus 검색 grant. 없으면 null
 * @param viewerReadUrl          Viewer Focus asset read URL. 없으면 null
 * @param turnAttachmentReadUrls 현재 turn 첨부 attachment_id → read URL. 현재 첨부마다 반드시 있어야 한다
 */
public record RunAssetAccess(String assetAccessGrant, String viewerReadUrl, Map<String, String> turnAttachmentReadUrls) {

    /** 텍스트·빈 자산 경로: 자격증명이 필요 없다. */
    public static final RunAssetAccess NONE = new RunAssetAccess(null, null, Map.of());

    public RunAssetAccess {
        turnAttachmentReadUrls = turnAttachmentReadUrls == null ? Map.of() : Map.copyOf(turnAttachmentReadUrls);
    }

    @Override
    public String toString() {
        return "RunAssetAccess[grant=" + (assetAccessGrant == null ? "absent" : "<redacted>")
                + ", viewerReadUrl=" + (viewerReadUrl == null ? "absent" : "<redacted>")
                + ", turnAttachmentReadUrls=" + turnAttachmentReadUrls.size() + "]";
    }
}
