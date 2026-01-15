package com.proovy.global.infra.s3;

import java.util.List;

/**
 * S3 파일 관리 서비스
 */
public interface S3Service {

    /**
     * S3에서 파일 삭제
     * @param s3Key S3 저장 경로
     */
    void deleteFile(String s3Key);

    /**
     * S3에서 여러 파일 일괄 삭제
     * @param s3Keys S3 저장 경로 목록
     */
    void deleteFiles(List<String> s3Keys);

    /**
     * S3 파일의 공개 URL 반환
     * @param s3Key S3 저장 경로
     * @return 공개 URL
     */
    String getFileUrl(String s3Key);

    /**
     * 썸네일 URL 반환 (썸네일 S3 키가 있는 경우)
     * @param thumbnailS3Key 썸네일 S3 키
     * @return 썸네일 URL 또는 null
     */
    String getThumbnailUrl(String thumbnailS3Key);
}
