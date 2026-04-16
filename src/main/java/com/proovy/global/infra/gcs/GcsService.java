package com.proovy.global.infra.gcs;

import java.io.InputStream;
import java.util.List;

public interface GcsService {

    /**
     * GCS에서 파일 삭제
     * @param gcsKey GCS 저장 경로
     */
    void deleteFile(String gcsKey);

    /**
     * GCS에서 여러 파일 일괄 삭제
     * @param gcsKeys GCS 저장 경로 목록
     */
    void deleteFiles(List<String> gcsKeys);

    /**
     * 파일 업로드
     * @param gcsKey GCS 저장 경로
     * @param inputStream 파일 스트림
     * @param contentLength 파일 크기
     * @param contentType 파일 타입
     * @return 업로드된 파일 URL
     */
    String uploadFile(String gcsKey, InputStream inputStream, long contentLength, String contentType);

    /**
     * 파일 URL 생성
     * @param gcsKey GCS 저장 경로
     * @return GCS 파일 URL
     */
    String getFileUrl(String gcsKey);

    /**
     * 썸네일 URL 생성
     * @param thumbnailGcsKey 썸네일 GCS 저장 경로
     * @return 썸네일 URL (없으면 null)
     */
    String getThumbnailUrl(String thumbnailGcsKey);

    /**
     * 파일 존재 여부 확인
     * @param gcsKey GCS 저장 경로
     * @return 존재 여부
     */
    boolean doesFileExist(String gcsKey);

    /**
     * 파일 업로드용 Signed URL 생성 (PUT)
     * @param gcsKey GCS 저장 경로
     * @param contentType 파일 타입
     * @param durationMinutes URL 유효 시간 (분)
     * @return Signed URL
     */
    String generatePresignedUploadUrl(String gcsKey, String contentType, int durationMinutes);

    /**
     * 파일 다운로드용 Signed URL 생성 (GET)
     * @param gcsKey GCS 저장 경로
     * @param fileName 다운로드 시 파일명
     * @param durationMinutes URL 유효 시간 (분)
     * @return Signed URL
     */
    String generatePresignedDownloadUrl(String gcsKey, String fileName, int durationMinutes);

    /**
     * GCS에서 파일 바이트 직접 읽기 (서버 내부용, 버킷 공개 여부 무관)
     * @param gcsKey GCS 저장 경로
     * @return 파일 바이트 배열
     */
    byte[] readFileBytes(String gcsKey);
}
