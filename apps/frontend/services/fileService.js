import axios, { isCancel, CancelToken } from 'axios';
import axiosInstance from './axios';
import { Toast } from '../components/Toast';

class FileService {
  constructor() {
    this.baseUrl = process.env.NEXT_PUBLIC_API_URL;
    this.uploadLimit = 50 * 1024 * 1024; // 50MB
    this.retryAttempts = 3;
    this.retryDelay = 1000;
    this.activeUploads = new Map();

    this.allowedTypes = {
      image: {
        extensions: ['.jpg', '.jpeg', '.png', '.gif', '.webp'],
        mimeTypes: ['image/jpeg', 'image/png', 'image/gif', 'image/webp'],
        maxSize: 10 * 1024 * 1024, //10MB
        name: '이미지'
      },
      document: {
        extensions: ['.pdf'],
        mimeTypes: ['application/pdf'],
        maxSize: 20 * 1024 * 1024, //20MB
        name: 'PDF 문서'
      }
    };
  }

  async validateFile(file) {

    if (!file) {
      const message = '파일이 선택되지 않았습니다.';
      Toast.error(message);
      return { success: false, message };
    }

    if (file.size > this.uploadLimit) {
      const message = `파일 크기는 ${this.formatFileSize(this.uploadLimit)}를 초과할 수 없습니다.`;
      Toast.error(message);
      return { success: false, message };
    }

    let isAllowedType = false;
    let maxTypeSize = 0;
    let typeConfig = null;

    for (const config of Object.values(this.allowedTypes)) {
      if (config.mimeTypes.includes(file.type)) {
        isAllowedType = true;
        maxTypeSize = config.maxSize;
        typeConfig = config;
        break;
      }
    }

    if (!isAllowedType) {
      const message = '지원하지 않는 파일 형식입니다.';
      Toast.error(message);
      return { success: false, message };
    }

    if (file.size > maxTypeSize) {
      const message = `${typeConfig.name} 파일은 ${this.formatFileSize(maxTypeSize)}를 초과할 수 없습니다.`;
      Toast.error(message);
      return { success: false, message };
    }

    const ext = this.getFileExtension(file.name);
    if (!typeConfig.extensions.includes(ext.toLowerCase())) {
      const message = '파일 확장자가 올바르지 않습니다.';
      Toast.error(message);
      return { success: false, message };
    }

    return { success: true };
  }

  async uploadFile(file, onProgress, token, sessionId) {

    const validationResult = await this.validateFile(file);
    if (!validationResult.success) {
      return validationResult;
    }

    try {
      
      const fileData = {                          
        originalName: file.name,
        mimeType: file.type,
        size: file.size,
      }

      const response = await fetch(`${process.env.NEXT_PUBLIC_API_URL}/api/files/upload`, {
        method: 'POST',
        headers: {
         'Content-Type': 'application/json', 
        },
        body: JSON.stringify(fileData),
      });

      //그린: 백엔드와 상의 필요
      const uploadUrl = response.s3Url;

      const source = CancelToken.source();
      this.activeUploads.set(file.name, source);

      // token과 sessionId는 axios 인터셉터에서 자동으로 추가되므로
      // 여기서는 명시적으로 전달하지 않아도 됩니다
      // 그린: s3에 파일 업로드
      const uploadResponse = await axios.put(uploadUrl, file, {
        headers: {
          'Content-Type': file.type,
        },
        cancelToken: source.token,
        //그린: 인증 여부 주석처리 안하면 axios error 발생 -> 원인? 모르겠음
        //withCredentials: true,
        onUploadProgress: (progressEvent) => {
          if (onProgress) {
            const percentCompleted = Math.round(
              (progressEvent.loaded * 100) / progressEvent.total
            );
            onProgress(percentCompleted);
          }
        }
      });

      console.log('이미지 post 결과: ',response);

      this.activeUploads.delete(uuid);

      //s3에 업로드를 실패한 경우
      if (response.status < 200 || response.status >= 300) {
        return {
          success: false,
          message: response.data?.message || '파일 업로드에 실패했습니다.'
        };
      }

      return {
        success: true,
        file: fileData,
      };

    } 
    catch (error) {
      this.activeUploads.delete(file.name);

      if (isCancel(error)) {
        return {
          success: false,
          message: '업로드가 취소되었습니다.'
        };
      }

      if (error.response?.status === 401) {
        throw new Error('Authentication expired. Please login again.');
      }

      return this.handleUploadError(error);
    }
  }
  
  async downloadFile(filename, originalname, token, sessionId) {
    try {

      console.log('파일네임: ', filename);
      const fileUrl = `https://dypusta48vkr4.cloudfront.net/chat/${filename}`;

      // 굳이 axios 쓸 수도 있고, a태그 클릭으로 더 심플하게도 가능
      const response = await axios.get(fileUrl, {
        responseType: 'blob',     // 실제 바이트 받기
        withCredentials: false,   // ❗ S3에는 creds 필요 없음
      });

      const blob = new Blob([response.data], {
        type: response.headers['content-type'] || 'application/octet-stream',
      });

      const blobUrl = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      //link.download = finalFilename;
      link.download = originalname || fileId;
      link.style.display = 'none';
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);

      setTimeout(() => {
        window.URL.revokeObjectURL(blobUrl);
      }, 100);

      return { success: true };

    } catch (error) {
      if (error.response?.status === 401) {
        throw new Error('Authentication expired. Please login again.');
      }

      return this.handleDownloadError(error);
    }
  }

  getFileUrl(filename, forPreview = false) {
    if (!filename) return '';

    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const endpoint = forPreview ? 'view' : 'download';
    return `${baseUrl}/api/files/${endpoint}/${filename}`;
  }

  getPreviewUrl(file, token, sessionId, withAuth = true) {
    if (!file?.filename) return '';

    const baseUrl = `${process.env.NEXT_PUBLIC_API_URL}/api/files/view/${file.filename}`;

    if (!withAuth) return baseUrl;

    if (!token || !sessionId) return baseUrl;

    // URL 객체 생성 전 프로토콜 확인
    const url = new URL(baseUrl);
    url.searchParams.append('token', encodeURIComponent(token));
    url.searchParams.append('sessionId', encodeURIComponent(sessionId));

    return url.toString();
  }

  getFileType(filename) {
    if (!filename) return 'unknown';
    const ext = this.getFileExtension(filename).toLowerCase();
    for (const [type, config] of Object.entries(this.allowedTypes)) {
      if (config.extensions.includes(ext)) {
        return type;
      }
    }
    return 'unknown';
  }

  getFileExtension(filename) {
    if (!filename) return '';
    const parts = filename.split('.');
    return parts.length > 1 ? `.${parts.pop().toLowerCase()}` : '';
  }

  formatFileSize(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return `${parseFloat((bytes / Math.pow(1024, i)).toFixed(2))} ${units[i]}`;
  }

  getHeaders(token, sessionId) {
    if (!token || !sessionId) {
      return {
        'Accept': 'application/json, */*'
      };
    }
    return {
      'x-auth-token': token,
      'x-session-id': sessionId,
      'Accept': 'application/json, */*'
    };
  }

  handleUploadError(error) {
    console.error('Upload error:', error);

    if (error.code === 'ECONNABORTED') {
      return {
        success: false,
        message: '파일 업로드 시간이 초과되었습니다.'
      };
    }

    if (axios.isAxiosError(error)) {
      const status = error.response?.status;
      const message = error.response?.data?.message;

      switch (status) {
        case 400:
          return {
            success: false,
            message: message || '잘못된 요청입니다.'
          };
        case 401:
          return {
            success: false,
            message: '인증이 필요합니다.'
          };
        case 413:
          return {
            success: false,
            message: '파일이 너무 큽니다.'
          };
        case 415:
          return {
            success: false,
            message: '지원하지 않는 파일 형식입니다.'
          };
        case 500:
          return {
            success: false,
            message: '서버 오류가 발생했습니다.'
          };
        default:
          return {
            success: false,
            message: message || '파일 업로드에 실패했습니다.'
          };
      }
    }

    return {
      success: false,
      message: error.message || '알 수 없는 오류가 발생했습니다.',
      error
    };
  }

  handleDownloadError(error) {
    console.error('Download error:', error);

    if (error.code === 'ECONNABORTED') {
      return {
        success: false,
        message: '파일 다운로드 시간이 초과되었습니다.'};
    }

    if (axios.isAxiosError(error)) {
      const status = error.response?.status;
      const message = error.response?.data?.message;

      switch (status) {
        case 404:
          return {
            success: false,
            message: '파일을 찾을 수 없습니다.'
          };
        case 403:
          return {
            success: false,
            message: '파일에 접근할 권한이 없습니다.'
          };
        case 400:
          return {
            success: false,
            message: message || '잘못된 요청입니다.'
          };
        case 500:
          return {
            success: false,
            message: '서버 오류가 발생했습니다.'
          };
        default:
          return {
            success: false,
            message: message || '파일 다운로드에 실패했습니다.'
          };
      }
    }

    return {
      success: false,
      message: error.message || '알 수 없는 오류가 발생했습니다.',
      error
    };
  }

  cancelUpload(filename) {
    const source = this.activeUploads.get(filename);
    if (source) {
      source.cancel('Upload canceled by user');
      this.activeUploads.delete(filename);
      return {
        success: true,
        message: '업로드가 취소되었습니다.'
      };
    }
    return {
      success: false,
      message: '취소할 업로드를 찾을 수 없습니다.'
    };
  }

  cancelAllUploads() {
    let canceledCount = 0;
    for (const [filename, source] of this.activeUploads) {
      source.cancel('All uploads canceled');
      this.activeUploads.delete(filename);
      canceledCount++;
    }
    
    return {
      success: true,
      message: `${canceledCount}개의 업로드가 취소되었습니다.`,
      canceledCount
    };
  }

  getErrorMessage(status) {
    switch (status) {
      case 400:
        return '잘못된 요청입니다.';
      case 401:
        return '인증이 필요합니다.';
      case 403:
        return '파일에 접근할 권한이 없습니다.';
      case 404:
        return '파일을 찾을 수 없습니다.';
      case 413:
        return '파일이 너무 큽니다.';
      case 415:
        return '지원하지 않는 파일 형식입니다.';
      case 500:
        return '서버 오류가 발생했습니다.';
      case 503:
        return '서비스를 일시적으로 사용할 수 없습니다.';
      default:
        return '알 수 없는 오류가 발생했습니다.';
    }
  }

  isRetryableError(error) {
    if (!error.response) {
      return true; // 네트워크 오류는 재시도 가능
    }

    const status = error.response.status;
    return [408, 429, 500, 502, 503, 504].includes(status);
  }
}

export default new FileService();