package com.ktb.chatapp.dto;

import com.ktb.chatapp.model.File;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PresignedUrlResponse {
    private String url;        // presigned URL (S3 업로드용)
    private String accessUrl;  // CloudFront URL (조회용)
    private long expiresIn;    // seconds
    private File file;      // 업로드된 파일 메타데이터
}
