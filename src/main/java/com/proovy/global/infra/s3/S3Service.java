package com.proovy.global.infra.s3;

import java.io.InputStream;
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
     * 파일 업로드
     * @param s3Key S3 저장 경로
     * @param inputStream 파일 스트림
     * @param contentLength 파일 크기
     * @param contentType 파일 타입
     * @return 업로드된 파일 URL
     */
    String uploadFile(String s3Key, InputStream inputStream, long contentLength, String contentType);

    /**
     * 파일 URL 생성
     * @param s3Key S3 저장 경로
     * @return S3 파일 URL
     */
    String getFileUrl(String s3Key);

    /**
     * 썸네일 URL 생성
     * @param thumbnailS3Key 썸네일 S3 저장 경로
     * @return 썸네일 URL (없으면 null)
     */
    String getThumbnailUrl(String thumbnailS3Key);

    /**
     * 파일 존재 여부 확인
     * @param s3Key S3 저장 경로
     * @return 존재 여부
     */
    boolean doesFileExist(String s3Key);
}
