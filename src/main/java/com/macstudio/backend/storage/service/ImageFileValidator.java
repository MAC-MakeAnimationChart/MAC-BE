package com.macstudio.backend.storage.service;

import com.macstudio.backend.global.exception.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

/** 클라이언트 MIME 선언 대신 실제 바이트와 디코딩 결과로 PNG/JPEG를 검증한다. */
public final class ImageFileValidator {
    private ImageFileValidator() {}

    public static ValidatedImage validate(MultipartFile image, long maxBytes) {
        if (image == null || image.isEmpty()) {
            throw bad("EMPTY_IMAGE", "업로드할 PNG 또는 JPEG 이미지를 선택해 주세요.");
        }
        if (image.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE",
                    "이미지 크기가 허용 범위를 초과했습니다.");
        }
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException exception) {
            throw bad("INVALID_IMAGE", "이미지 파일을 읽을 수 없습니다.");
        }

        String contentType;
        String extension;
        if (isPng(bytes)) {
            contentType = "image/png";
            extension = ".png";
        } else if (isJpeg(bytes)) {
            contentType = "image/jpeg";
            extension = ".jpg";
        } else {
            throw bad("UNSUPPORTED_IMAGE", "PNG 또는 JPEG 이미지만 업로드할 수 있습니다.");
        }
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null || decoded.getWidth() < 1 || decoded.getHeight() < 1) {
                throw bad("INVALID_IMAGE", "올바른 이미지 파일이 아닙니다.");
            }
        } catch (IOException exception) {
            throw bad("INVALID_IMAGE", "올바른 이미지 파일이 아닙니다.");
        }
        return new ValidatedImage(bytes, contentType, extension);
    }

    private static boolean isPng(byte[] value) {
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (value.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (value[i] != signature[i]) return false;
        return true;
    }

    private static boolean isJpeg(byte[] value) {
        return value.length >= 3 && value[0] == (byte) 0xff
                && value[1] == (byte) 0xd8 && value[2] == (byte) 0xff;
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public record ValidatedImage(byte[] bytes, String contentType, String extension) {}
}
