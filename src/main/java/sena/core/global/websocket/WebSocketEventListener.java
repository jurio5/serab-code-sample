package sena.core.global.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private static final long RECONNECT_GRACE_PERIOD = 10000;

    private final WebSocketSessionRegistry sessionRegistry;

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        Principal user = accessor.getUser();

        if (user == null) {
            log.debug("WebSocket Connected: sessionId={}, user=anonymous", sessionId);
            return;
        }

        Long memberId = parseMemberId(user.getName());
        if (memberId == null) {
            return;
        }

        sessionRegistry.addConnectedSession(memberId, sessionId);

        log.info("WebSocket Connected: sessionId={}, memberId={}", sessionId, memberId);
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        Principal user = accessor.getUser();

        if (user != null) {
            Long memberId = parseMemberId(user.getName());
            if (memberId != null) {
                log.info("WebSocket Disconnected: sessionId={}, memberId={}", sessionId, memberId);
                if (sessionRegistry.removeConnectedSession(memberId, sessionId)) {
                    sessionRegistry.scheduleOffline(memberId, Instant.now().plusMillis(RECONNECT_GRACE_PERIOD));
                }
            }
        } else {
            log.debug("WebSocket Disconnected: sessionId={}, user=anonymous", sessionId);
        }
    }

    private Long parseMemberId(String name) {
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException e) {
            log.warn("Invalid memberId format: {}", name);
            return null;
        }
    }

    public int getConnectedUserCount() {
        return sessionRegistry.getConnectedUserCount();
    }
}
