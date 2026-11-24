package com.macstudio.backend.storage.dto;

/** 로컬 S3 연결 점검에서 업로드와 HEAD 확인이 끝난 객체 메타데이터를 반환한다. */
public record S3ConnectivityUploadResponse(
        String objectKey,
        String contentType,
        long sizeBytes,
        String eTag,
        boolean verified
) {}
