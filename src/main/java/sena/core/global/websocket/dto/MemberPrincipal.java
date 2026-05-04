package sena.core.global.websocket.dto;

import java.security.Principal;

public record MemberPrincipal(Long memberId, String nickname, String role) implements Principal {
    @Override
    public String getName() {
        return String.valueOf(memberId);
    }
}
