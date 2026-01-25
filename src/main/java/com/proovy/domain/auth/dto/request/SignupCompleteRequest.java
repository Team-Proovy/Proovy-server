package com.proovy.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupCompleteRequest(
        @NotBlank(message = "회원가입 토큰은 필수입니다.")
        String signupToken,

        @NotBlank(message = "이름은 필수입니다.")
        @Size(min = 1, max = 50, message = "이름은 1~50자로 입력해주세요.")
        String name,

        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(min = 2, max = 10, message = "닉네임은 2~10자로 입력해주세요.")
        String nickname,

        @NotBlank(message = "학부/과는 필수입니다.")
        @Size(max = 100, message = "학부/과는 100자 이내로 입력해주세요.")
        String department,

        @NotBlank(message = "서비스를 알게 된 경로는 필수입니다.")
        @Size(max = 20, message = "알게 된 경로는 20자 이내로 입력해주세요.")
        String referralSource
) {}
