package com.ktb.chatapp.service;

import com.ktb.chatapp.controller.FileMetadataRequest;
import com.ktb.chatapp.dto.PresignedUrlResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3FileService {

    private final S3Presigner s3Presigner;
    private final FileRepository fileRepository;

    @Value("${app.s3.bucket}")
    private String bucket;

    @Value("${app.cloudfront.base-url}")
    private String cloudfrontBaseUrl;

    private static final long PRESIGNED_URL_EXPIRATION_MINUTES = 10;

    /**
     * Presigned URL 생성 + DB 저장
     */
    public PresignedUrlResponse generatePresignedUrl(FileMetadataRequest metadata, String prefix, String uploaderId) {
        String extension = getExtension(metadata.getOriginalname());
        String fileId = UUID.randomUUID().toString();
        String key = prefix + "/" + fileId + extension;

        // Presigned URL 생성
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(metadata.getMimeType())
                .contentLength(metadata.getSize())
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(PRESIGNED_URL_EXPIRATION_MINUTES))
                .putObjectRequest(putObjectRequest)
                .build();

        PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
        String url = presignedRequest.url().toString();

        // DB 저장
        File fileEntity = File.builder()
                .filename(key)
                .originalname(metadata.getOriginalname())
                .mimetype(metadata.getMimeType())
                .size(metadata.getSize())
                .path(key)
                .user(uploaderId)
                .uploadDate(LocalDateTime.now())
                .build();

        File savedFile = fileRepository.save(fileEntity);
        String accessUrl = cloudfrontBaseUrl + "/" + key;
        log.info("Generated presigned URL for key: {}, accessUrl: {}", key, accessUrl);

        return PresignedUrlResponse.builder()
                .url(url)
                .accessUrl(accessUrl)
                .expiresIn(PRESIGNED_URL_EXPIRATION_MINUTES * 60)
                .file(savedFile)
                .build();
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf("."));
    }
}
