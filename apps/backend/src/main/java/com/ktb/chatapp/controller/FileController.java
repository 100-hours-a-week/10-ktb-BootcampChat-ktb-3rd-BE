package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.PresignedUrlResponse;
import com.ktb.chatapp.dto.StandardResponse;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.S3FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

@Tag(name = "파일 (Files)", description = "파일 업로드 및 다운로드 API")
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final S3FileService s3FileService;
    private final UserRepository userRepository;

//    @PostMapping("/metadata")
//    public ResponseEntity<?> saveFileMetadata(
//            Principal principal,
//            @RequestBody FileMetadataRequest req
//    ) {
//        User user = userRepository.findByEmail(principal.getName())
//                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
//
//        File file = File.builder()
//                .filename(req.getFilename())
//                .originalname(req.getOriginalname())
//                .mimetype(req.getMimeType())
//                .size(req.getSize())
//                .build();
//
//        File saved = fileRepository.save(file);
//
//        return ResponseEntity.ok(saved);
//    }


    /**
     * Presigned URL 발급 (S3 직접 업로드용)
     */
    @Operation(summary = "Presigned URL 발급", description = "S3 직접 업로드를 위한 Presigned URL을 발급합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Presigned URL 발급 성공",
            content = @Content(schema = @Schema(implementation = PresignedUrlResponse.class))),
        @ApiResponse(responseCode = "401", description = "인증 실패",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @Parameter(description = "파일 메타데이터") @RequestBody FileMetadataRequest metadata,
            Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(
                    StandardResponse.error("인증이 필요합니다."));
        }

        try {
            User user = userRepository.findByEmail(principal.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + principal.getName()));

            PresignedUrlResponse response = s3FileService.generatePresignedUrl(metadata, "chat", user.getId());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Presigned URL 발급 중 에러 발생", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Presigned URL 발급 중 오류가 발생했습니다.");
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

// TODO: S3 방식으로 전환 후 아래 메서드들은 사용하지 않음 (S3 URL로 직접 접근)
//    /**
//     * 보안이 강화된 파일 다운로드
//     */
//    @GetMapping("/download/{filename:.+}")
//    public ResponseEntity<?> downloadFile(...) { ... }
//
//    @GetMapping("/view/{filename:.+}")
//    public ResponseEntity<?> viewFile(...) { ... }
//
//    @DeleteMapping("/{id}")
//    public ResponseEntity<?> deleteFile(...) { ... }
}