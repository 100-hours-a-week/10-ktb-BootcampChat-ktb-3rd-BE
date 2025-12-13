package com.ktb.chatapp.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PresignedUrlResponse {
    private String url;        // presigned URL (S3 업로드용)
    private String accessUrl;  // CloudFront URL (조회용)
    private long expiresIn;    // seconds
}
