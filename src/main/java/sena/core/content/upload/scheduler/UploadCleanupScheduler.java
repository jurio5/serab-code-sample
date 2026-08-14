package sena.core.content.upload.scheduler;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sena.core.content.upload.service.UploadCleanupService;
import sena.core.global.scheduler.SchedulerStatusTracker;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class UploadCleanupScheduler {

    private static final String NAME = "pending-upload-cleanup";

    private final UploadCleanupService uploadCleanupService;
    private final SchedulerStatusTracker tracker;

    @PostConstruct
    void init() {
        tracker.register(NAME, "사용되지 않은 임시 업로드 정리", "15분마다");
    }

    @Scheduled(cron = "0 */15 * * * *")
    @SchedulerLock(
            name = "UploadCleanupScheduler.cleanupExpiredUploads",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT5S")
    public void cleanupExpiredUploads() {
        tracker.recordStart(NAME);
        try {
            int cleaned = uploadCleanupService.cleanupExpiredUploads(LocalDateTime.now());
            tracker.recordSuccess(NAME, cleaned > 0 ? cleaned + "개 파일 정리" : "정리 대상 없음");
            if (cleaned > 0) {
                log.info("Expired upload cleanup completed: {} objects deleted", cleaned);
            }
        } catch (Exception e) {
            tracker.recordFailure(NAME, e.getMessage());
            throw e;
        }
    }
}
