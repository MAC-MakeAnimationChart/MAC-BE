package com.macstudio.backend.auth.dto;

import com.macstudio.backend.member.entity.ConsentDocumentType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 사용자가 동의한 문서 종류와 버전. */
public record AgreedTermsRequest(
        @NotNull
        ConsentDocumentType type,

        @NotBlank
        @Size(max = 40)
        String version
) {
}