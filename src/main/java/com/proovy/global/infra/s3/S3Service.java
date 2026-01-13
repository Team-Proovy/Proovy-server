package com.proovy.global.infra.s3;

import java.util.List;

/**
 * S3 파일 관리 서비스
 * TODO: AWS S3 SDK 연동 후 실제 구현 필요
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
}
