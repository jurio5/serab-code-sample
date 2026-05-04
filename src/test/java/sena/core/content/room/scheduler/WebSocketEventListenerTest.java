package sena.core.content.room.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import sena.core.content.room.service.RoomMemberService;
import sena.core.global.websocket.WebSocketEventListener;
import sena.core.global.websocket.WebSocketSessionRegistry;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WebSocketEventListenerTest {

    @Mock
    private RoomMemberService roomMemberService;

    private WebSocketEventListener webSocketEventListener;
    private WebSocketRoomPresenceScheduler webSocketRoomPresenceScheduler;

    @BeforeEach
    void setUp() {
        WebSocketSessionRegistry sessionRegistry = new InMemoryWebSocketSessionRegistry();
        webSocketEventListener = new WebSocketEventListener(sessionRegistry);
        webSocketRoomPresenceScheduler = new WebSocketRoomPresenceScheduler(sessionRegistry, roomMemberService);
    }

    @Test
    @DisplayName("연결 해제 후 스케줄러 실행 시 오프라인 전환을 처리")
    void disconnect_schedulesOfflineTransition() {
        // given
        webSocketEventListener.handleWebSocketConnectListener(connectEvent("session-1", 1L));
        webSocketEventListener.handleWebSocketDisconnectListener(disconnectEvent("session-1", 1L));

        // when
        webSocketRoomPresenceScheduler.processPendingOfflineMembers(Instant.now().plusSeconds(11));

        // then
        verify(roomMemberService).markRoomsOffline(1L);
    }

    @Test
    @DisplayName("재연결 시 스케줄러 실행 전 오프라인 전환 예약이 취소됨")
    void reconnect_cancelsPendingOfflineTransition() {
        // given
        webSocketEventListener.handleWebSocketConnectListener(connectEvent("session-1", 1L));
        webSocketEventListener.handleWebSocketDisconnectListener(disconnectEvent("session-1", 1L));
        webSocketEventListener.handleWebSocketConnectListener(connectEvent("session-2", 1L));

        // when
        webSocketRoomPresenceScheduler.processPendingOfflineMembers(Instant.now().plusSeconds(11));

        // then
        verify(roomMemberService, never()).markRoomsOffline(1L);
    }

    private SessionConnectEvent connectEvent(String sessionId, Long memberId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        accessor.setUser(principal(memberId));
        return new SessionConnectEvent(this, message(accessor));
    }

    private SessionDisconnectEvent disconnectEvent(String sessionId, Long memberId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(sessionId);
        accessor.setUser(principal(memberId));
        return new SessionDisconnectEvent(this, message(accessor), sessionId, CloseStatus.NORMAL);
    }

    private Message<byte[]> message(StompHeaderAccessor accessor) {
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Principal principal(Long memberId) {
        return () -> String.valueOf(memberId);
    }

    private static class InMemoryWebSocketSessionRegistry extends WebSocketSessionRegistry {

        private final Map<Long, Set<String>> connectedUsers = new ConcurrentHashMap<>();
        private final Map<Long, Instant> pendingOfflineMembers = new ConcurrentHashMap<>();

        private InMemoryWebSocketSessionRegistry() {
            super(null);
        }

        @Override
        public void addConnectedSession(Long memberId, String sessionId) {
            pendingOfflineMembers.remove(memberId);
            connectedUsers.computeIfAbsent(memberId, key -> ConcurrentHashMap.newKeySet()).add(sessionId);
        }

        @Override
        public boolean removeConnectedSession(Long memberId, String sessionId) {
            Set<String> sessions = connectedUsers.get(memberId);
            if (sessions == null || !sessions.remove(sessionId)) {
                return false;
            }

            if (sessions.isEmpty()) {
                connectedUsers.remove(memberId);
            }
            return true;
        }

        @Override
        public void scheduleOffline(Long memberId, Instant deadline) {
            pendingOfflineMembers.put(memberId, deadline);
        }

        @Override
        public List<Long> findExpiredOfflineMembers(Instant now) {
            return pendingOfflineMembers.entrySet().stream()
                    .filter(entry -> !entry.getValue().isAfter(now))
                    .map(Map.Entry::getKey)
                    .toList();
        }

        @Override
        public boolean shouldMarkOffline(Long memberId, Instant now) {
            Instant deadline = pendingOfflineMembers.get(memberId);
            if (deadline == null || deadline.isAfter(now)) {
                return false;
            }

            Set<String> sessions = connectedUsers.get(memberId);
            if (sessions != null && !sessions.isEmpty()) {
                pendingOfflineMembers.remove(memberId);
                return false;
            }

            pendingOfflineMembers.remove(memberId);
            connectedUsers.remove(memberId);
            return true;
        }
    }
}
