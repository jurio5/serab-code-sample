package sena.core.content.room.scheduler;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sena.core.content.room.enums.RoomMemberStatus;
import sena.core.content.room.repository.RoomMemberRepository;
import sena.core.global.scheduler.SchedulerStatusTracker;
import sena.core.global.websocket.WebSocketSessionRegistry;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketActiveMemberReconciliationScheduler {

    private static final String NAME = "ws-presence-reconcile";
    private static final long RECONCILE_GRACE_PERIOD = 10000;

    private final RoomMemberRepository roomMemberRepository;
    private final WebSocketSessionRegistry sessionRegistry;
    private final SchedulerStatusTracker tracker;

    @PostConstruct
    void init() {
        tracker.register(NAME, "30초마다 ACTIVE 참여자 연결 상태 보정", "30초마다");
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    @SchedulerLock(name = "WebSocketActiveMemberReconciliationScheduler.reconcileActiveMembers", lockAtMostFor = "PT1M", lockAtLeastFor = "PT5S")
    public void reconcileActiveMembers() {
        tracker.recordStart(NAME);
        try {
            Instant now = Instant.now();
            List<Long> staleMemberIds = roomMemberRepository.findDistinctMemberIdsByStatus(RoomMemberStatus.ACTIVE)
                    .stream()
                    .filter(memberId -> !sessionRegistry.hasActiveSession(memberId))
                    .toList();

            staleMemberIds.forEach(memberId ->
                    sessionRegistry.scheduleOffline(memberId, now.plusMillis(RECONCILE_GRACE_PERIOD)));

            String message = staleMemberIds.isEmpty()
                    ? "보정 대상 없음"
                    : staleMemberIds.size() + "명 오프라인 보정";

            if (!staleMemberIds.isEmpty()) {
                log.info("WebSocket presence reconcile completed: {} stale active members scheduled for offline verification", staleMemberIds.size());
            }
            tracker.recordSuccess(NAME, message);
        } catch (Exception e) {
            tracker.recordFailure(NAME, e.getMessage());
            throw e;
        }
    }
}
