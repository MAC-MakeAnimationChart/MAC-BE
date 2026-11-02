package com.macstudio.backend.global.exception;

import com.macstudio.backend.global.dto.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    /** 예상하지 못한 오류에서도 DB 정보·비밀값·내부 예외 메시지를 노출하지 않는다. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) {
        if (ignored instanceof org.springframework.web.ErrorResponse error) {
            return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                    .body(new ErrorResponse("HTTP_" + error.getStatusCode().value(), "요청 경로·메서드·형식을 확인해 주세요."));
        }
        return ResponseEntity.internalServerError().body(new ErrorResponse("INTERNAL_ERROR", "요청 처리 중 오류가 발생했습니다."));
    }
    /** 업무 오류를 코드와 안전한 메시지로 변환한다. */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> business(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage()));
    }
    /** DTO 검증·JSON 오류에 포함된 원문 비밀번호·토큰은 응답하지 않는다. */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", "요청 형식을 확인해 주세요."));
    }
    /** 기존 회원 서비스의 상태 예외를 공통 응답으로 변환한다. */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponse> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(new ErrorResponse(
                e.getStatusCode().value() == 401 ? "UNAUTHORIZED" : "REQUEST_REJECTED", "요청을 처리할 수 없습니다."));
    }
}
