package sena.core.global.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class WebSocketSessionRegistry {

    private static final Duration SESSION_TTL = Duration.ofSeconds(45);
    private static final Duration MEMBER_SET_TTL = SESSION_TTL.plusSeconds(10);

    private static final String SESSION_KEY_PREFIX = "ws:session:";
    private static final String MEMBER_SESSIONS_KEY_PREFIX = "ws:member:sessions:";
    private static final String PENDING_OFFLINE_KEY = "ws:presence:pending-offline";
    private static final String ONLINE_MEMBERS_KEY = "ws:presence:online-members";

    private final StringRedisTemplate redisTemplate;

    public void addConnectedSession(Long memberId, String sessionId) {
        refreshConnectedSession(memberId, sessionId);
    }

    public boolean refreshConnectedSession(Long memberId, String sessionId) {
        if (isInvalidSessionInput(memberId, sessionId)) {
            return false;
        }

        boolean sessionMissing = !Boolean.TRUE.equals(redisTemplate.hasKey(sessionKey(sessionId)));
        String memberKey = memberKey(memberId);
        String memberSessionsKey = memberSessionsKey(memberId);

        touchSession(sessionId, memberKey);
        trackSession(memberSessionsKey, sessionId);
        markMemberOnline(memberKey);
        clearPendingOffline(memberKey);
        return sessionMissing;
    }

    public boolean removeConnectedSession(Long memberId, String sessionId) {
        if (isInvalidSessionInput(memberId, sessionId)) {
            return false;
        }

        redisTemplate.delete(sessionKey(sessionId));
        redisTemplate.opsForSet().remove(memberSessionsKey(memberId), sessionId);
        return !hasActiveSession(memberId);
    }

    public void scheduleOffline(Long memberId, Instant deadline) {
        if (memberId == null || deadline == null) {
            return;
        }

        redisTemplate.opsForZSet().add(PENDING_OFFLINE_KEY, memberId.toString(), deadline.toEpochMilli());
    }

    public List<Long> findExpiredOfflineMembers(Instant now) {
        if (now == null) {
            return List.of();
        }

        Set<String> members = redisTemplate.opsForZSet().rangeByScore(PENDING_OFFLINE_KEY, 0, now.toEpochMilli());
        if (members == null || members.isEmpty()) {
            return List.of();
        }

        return members.stream()
                .map(Long::valueOf)
                .toList();
    }

    public boolean shouldMarkOffline(Long memberId, Instant now) {
        if (memberId == null || now == null) {
            return false;
        }

        String memberKey = memberKey(memberId);
        if (!isOfflineDeadlineReached(memberKey, now)) {
            return false;
        }

        if (hasActiveSession(memberId)) {
            clearPendingOffline(memberKey);
            return false;
        }

        clearMemberPresence(memberId);
        return true;
    }

    public boolean hasActiveSession(Long memberId) {
        if (memberId == null) {
            return false;
        }

        String memberKey = memberKey(memberId);
        String memberSessionsKey = memberSessionsKey(memberId);
        Set<String> sessions = trackedSessions(memberSessionsKey);

        if (sessions == null || sessions.isEmpty()) {
            clearMemberPresence(memberId);
            return false;
        }

        List<String> expiredSessions = new ArrayList<>();
        boolean hasActiveSession = hasAnyActiveSession(sessions, expiredSessions);

        if (!expiredSessions.isEmpty()) {
            redisTemplate.opsForSet().remove(memberSessionsKey, expiredSessions.toArray());
        }

        if (!hasActiveSession) {
            clearMemberPresence(memberId);
            return false;
        }

        touchMemberSessions(memberSessionsKey);
        markMemberOnline(memberKey);
        return true;
    }

    public int getConnectedUserCount() {
        Long count = redisTemplate.opsForSet().size(ONLINE_MEMBERS_KEY);
        return count != null ? count.intValue() : 0;
    }

    private boolean isInvalidSessionInput(Long memberId, String sessionId) {
        return memberId == null || sessionId == null || sessionId.isBlank();
    }

    private void touchSession(String sessionId, String memberKey) {
        redisTemplate.opsForValue().set(sessionKey(sessionId), memberKey, SESSION_TTL);
    }

    private void trackSession(String memberSessionsKey, String sessionId) {
        redisTemplate.opsForSet().add(memberSessionsKey, sessionId);
        touchMemberSessions(memberSessionsKey);
    }

    private void touchMemberSessions(String memberSessionsKey) {
        redisTemplate.expire(memberSessionsKey, MEMBER_SET_TTL);
    }

    private void markMemberOnline(String memberKey) {
        redisTemplate.opsForSet().add(ONLINE_MEMBERS_KEY, memberKey);
    }

    private void clearPendingOffline(String memberKey) {
        redisTemplate.opsForZSet().remove(PENDING_OFFLINE_KEY, memberKey);
    }

    private boolean isOfflineDeadlineReached(String memberKey, Instant now) {
        Double deadline = redisTemplate.opsForZSet().score(PENDING_OFFLINE_KEY, memberKey);
        return deadline != null && deadline <= now.toEpochMilli();
    }

    private Set<String> trackedSessions(String memberSessionsKey) {
        return redisTemplate.opsForSet().members(memberSessionsKey);
    }

    private boolean hasAnyActiveSession(Set<String> sessions, List<String> expiredSessions) {
        boolean hasActiveSession = false;
        for (String sessionId : sessions) {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(sessionKey(sessionId)))) {
                hasActiveSession = true;
            } else {
                expiredSessions.add(sessionId);
            }
        }
        return hasActiveSession;
    }

    private void clearMemberPresence(Long memberId) {
        String memberKey = memberKey(memberId);
        redisTemplate.delete(memberSessionsKey(memberId));
        redisTemplate.opsForSet().remove(ONLINE_MEMBERS_KEY, memberKey);
        clearPendingOffline(memberKey);
    }

    private String memberKey(Long memberId) {
        return memberId.toString();
    }

    private String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private String memberSessionsKey(Long memberId) {
        return MEMBER_SESSIONS_KEY_PREFIX + memberId;
    }
}
