package sena.core.content.room.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sena.core.content.room.service.RoomMemberService;
import sena.core.global.websocket.WebSocketSessionRegistry;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketRoomPresenceScheduler {

    private final WebSocketSessionRegistry sessionRegistry;
    private final RoomMemberService roomMemberService;

    @Scheduled(fixedDelay = 3000)
    @SchedulerLock(name = "WebSocketRoomPresenceScheduler.processPendingOfflineMembers", lockAtMostFor = "PT5S")
    public void processPendingOfflineMembers() {
        processPendingOfflineMembers(Instant.now());
    }

    void processPendingOfflineMembers(Instant now) {
        sessionRegistry.findExpiredOfflineMembers(now)
                .forEach(memberId -> {
                    if (sessionRegistry.shouldMarkOffline(memberId, now)) {
                        log.info("No reconnection for memberId={}, marking memberships offline", memberId);
                        roomMemberService.markRoomsOffline(memberId);
                    } else {
                        log.debug("MemberId={} reconnected, skipping offline transition", memberId);
                    }
                });
    }
}
