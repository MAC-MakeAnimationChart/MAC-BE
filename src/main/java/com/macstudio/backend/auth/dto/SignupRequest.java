package com.macstudio.backend.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 이메일·소셜 모두 약관 동의 뒤 동일한 가입 경로로 완료한다. 방식별 필드는 서비스가 검증한다. */
public record SignupRequest(
        @NotNull
        @Pattern(regexp = "EMAIL|SOCIAL")
        String signupType,

        @Size(max = 254)
        String email,

        @Size(max = 72)
        String password,

        @Size(max = 512)
        String verificationToken,

        @NotEmpty
        @Size(max = 2)
        List<@NotNull @Valid AgreedTermsRequest> agreedTerms
) {
    /** 이메일을 정규화하고 약관 목록을 복사한다. 비밀번호는 변경하지 않는다. */
    public SignupRequest {
        if (email != null) {
            email = email.strip().toLowerCase(Locale.ROOT);
        }

        if (agreedTerms != null) {
            agreedTerms = Collections.unmodifiableList(
                    new ArrayList<>(agreedTerms)
            );
        }
    }

    /** 비밀번호와 가입 증명 원문이 로그에 노출되지 않도록 한다. */
    @Override
    public String toString() {
        return "SignupRequest[REDACTED]";
    }
}
