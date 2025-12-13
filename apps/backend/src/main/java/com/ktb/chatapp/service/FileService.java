package com.ktb.chatapp.service;

import com.ktb.chatapp.controller.FileMetadataRequest;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileService {

//    FileUploadResult uploadFile(MultipartFile file, String uploaderId);
    FileUploadResult uploadFile(FileMetadataRequest file, String uploaderId);

    String storeFile(MultipartFile file, String subDirectory);

    Resource loadFileAsResource(String fileName, String requesterId);

    boolean deleteFile(String fileId, String requesterId);
}

