package sena.core.content.upload.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sena.core.content.upload.domain.UploadSession;
import sena.core.content.upload.enums.UploadStatus;
import sena.core.content.upload.monitoring.UploadLifecycleMetrics;
import sena.core.content.upload.repository.UploadSessionRepository;
import sena.core.global.storage.StorageService;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UploadCleanupService {

    private final UploadSessionRepository uploadSessionRepository;
    private final StorageService storageService;
    private final UploadLifecycleMetrics uploadLifecycleMetrics;

    public int cleanupExpiredUploads(LocalDateTime now) {
        List<UploadSession> sessions = uploadSessionRepository
                .findTop100ByStatusInAndExpiresAtBeforeOrderByIdAsc(
                        List.of(UploadStatus.PENDING, UploadStatus.UPLOADED),
                        now);
        int cleanedCount = 0;

        for (UploadSession session : sessions) {
            try {
                storageService.delete(session.getObjectKey());
                session.expire();
                uploadLifecycleMetrics.recordCleanupSuccess(session.getPurpose());
                cleanedCount++;
            } catch (RuntimeException e) {
                uploadLifecycleMetrics.recordCleanupFailure(session.getPurpose());
                log.warn("Expired upload cleanup failed: {}", session.getObjectKey(), e);
            }
        }
        return cleanedCount;
    }
}
