package com.macstudio.backend.global.dto;

/** 입력값·스택 정보 없이 반환하는 공통 오류 응답. */
public record ErrorResponse(String code, String message) {}
