package sena.core.global.security.jwt.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TokenValidationResult {
    CURRENT("현재 토큰", "새로 발급 필요"),
    PREVIOUS("이전 토큰", "이미 발급된 토큰 사용"),
    INVALID("유효하지 않음", "거부");

    private final String displayName;
    private final String description;
}
