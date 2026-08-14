package sena.core.content.upload.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sena.core.content.upload.domain.UploadSession;
import sena.core.content.upload.enums.UploadPurpose;
import sena.core.content.upload.enums.UploadStatus;
import sena.core.content.upload.monitoring.UploadLifecycleMetrics;
import sena.core.content.upload.repository.UploadSessionRepository;
import sena.core.content.upload.vo.PrepareUpload;
import sena.core.content.upload.vo.PreparedUpload;
import sena.core.content.upload.vo.ResolvedUpload;
import sena.core.content.upload.vo.UploadAttachment;
import sena.core.global.storage.StoragePresignedUpload;
import sena.core.global.storage.StorageService;
import sena.core.global.storage.StoredObjectMetadata;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static sena.core.global.exception.ErrorCode.*;

@Service
@RequiredArgsConstructor
@Transactional
public class UploadService {

    private static final Duration PRESIGNED_URL_DURATION = Duration.ofMinutes(10);
    private static final Duration PENDING_UPLOAD_RETENTION = Duration.ofHours(1);

    private final UploadSessionRepository uploadSessionRepository;
    private final StorageService storageService;
    private final UploadLifecycleMetrics uploadLifecycleMetrics;

    public List<PreparedUpload> prepareUploads(
            Long ownerId,
            UploadPurpose purpose,
            String folder,
            List<PrepareUpload> uploads) {
        if (uploads == null || uploads.isEmpty()) {
            return List.of();
        }
        String normalizedFolder = normalizeFolder(folder);
        LocalDateTime now = LocalDateTime.now();

        try {
            List<PreparedUpload> preparedUploads = uploads.stream()
                    .map(upload -> prepareUpload(
                            ownerId,
                            purpose,
                            normalizedFolder,
                            upload,
                            now))
                    .toList();
            uploadLifecycleMetrics.recordPrepared(purpose, preparedUploads.size());
            return preparedUploads;
        } catch (RuntimeException e) {
            uploadLifecycleMetrics.recordPrepareFailure(purpose, uploads.size());
            throw e;
        }
    }

    public void verifyUploads(Long ownerId, List<Long> uploadIds) {
        if (uploadIds == null || uploadIds.isEmpty()) {
            return;
        }
        validateUniqueIds(uploadIds);

        try {
            Map<Long, UploadSession> sessions = findSessions(uploadIds);
            LocalDateTime now = LocalDateTime.now();

            uploadIds.forEach(uploadId -> verifyUpload(
                    sessions.get(uploadId),
                    ownerId,
                    now));
            uploadLifecycleMetrics.recordCompleted(sessions.values());
        } catch (RuntimeException e) {
            uploadLifecycleMetrics.recordCompletionFailure(uploadIds.size());
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<ResolvedUpload> resolveAttachableUploads(
            Long ownerId,
            UploadPurpose purpose,
            List<Long> uploadIds) {
        if (uploadIds == null || uploadIds.isEmpty()) {
            return List.of();
        }
        validateUniqueIds(uploadIds);

        Map<Long, UploadSession> sessions = findSessions(uploadIds);
        LocalDateTime now = LocalDateTime.now();

        return uploadIds.stream()
                .map(uploadId -> resolveUpload(
                        sessions.get(uploadId),
                        ownerId,
                        purpose,
                        now))
                .toList();
    }

    public void attachUploads(
            Long ownerId,
            UploadPurpose purpose,
            List<UploadAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }

        try {
            List<Long> uploadIds = attachments.stream()
                    .map(UploadAttachment::uploadId)
                    .toList();
            validateUniqueIds(uploadIds);
            Map<Long, UploadSession> sessions = findSessions(uploadIds);
            LocalDateTime now = LocalDateTime.now();

            attachments.forEach(attachment -> {
                UploadSession session = sessions.get(attachment.uploadId());
                session.validateAttachable(ownerId, purpose, now);
                session.attach(attachment.targetId(), now);
            });
            uploadLifecycleMetrics.recordAttached(purpose, attachments.size());
        } catch (RuntimeException e) {
            uploadLifecycleMetrics.recordAttachFailure(purpose, attachments.size());
            throw e;
        }
    }

    public void markDeletedByObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        uploadSessionRepository.findByObjectKey(objectKey)
                .ifPresent(UploadSession::markDeleted);
    }

    public ResolvedUpload resolveAttachableUpload(
            Long ownerId,
            UploadPurpose purpose,
            Long uploadId) {
        if (uploadId == null || uploadId <= 0) {
            throw UPLOAD_SESSION_INVALID.toException();
        }
        return resolveAttachableUploads(ownerId, purpose, List.of(uploadId)).getFirst();
    }

    public void attachUpload(
            Long ownerId,
            UploadPurpose purpose,
            Long uploadId,
            Long targetId) {
        attachUploads(ownerId, purpose, List.of(UploadAttachment.of(uploadId, targetId)));
    }

    public boolean deleteAttachedUploads(UploadPurpose purpose, Long targetId) {
        if (purpose == null || targetId == null || targetId <= 0) {
            throw UPLOAD_SESSION_INVALID.toException();
        }
        List<UploadSession> sessions = uploadSessionRepository
                .findByPurposeAndAttachedTargetIdAndStatus(purpose, targetId, UploadStatus.ATTACHED);
        sessions.forEach(session -> {
            storageService.delete(session.getObjectKey());
            session.markDeleted();
        });
        return !sessions.isEmpty();
    }

    private PreparedUpload prepareUpload(
            Long ownerId,
            UploadPurpose purpose,
            String folder,
            PrepareUpload upload,
            LocalDateTime now) {
        String objectKey = folder + "/" + UUID.randomUUID() + "." + upload.extension();
        UploadSession session = uploadSessionRepository.save(UploadSession.create(
                ownerId,
                purpose,
                objectKey,
                upload.fileSize(),
                upload.contentType(),
                now.plus(PENDING_UPLOAD_RETENTION)));
        StoragePresignedUpload presignedUpload = storageService.createPresignedUpload(
                objectKey,
                upload.contentType(),
                PRESIGNED_URL_DURATION);

        return new PreparedUpload(
                session.getId(),
                presignedUpload.uploadUrl(),
                storageService.getPublicUrl(objectKey),
                presignedUpload.requiredHeaders(),
                now.plus(PRESIGNED_URL_DURATION));
    }

    private ResolvedUpload resolveUpload(
            UploadSession session,
            Long ownerId,
            UploadPurpose purpose,
            LocalDateTime now) {
        if (session == null) {
            throw UPLOAD_SESSION_NOT_FOUND.toException();
        }
        session.validateAttachable(ownerId, purpose, now);

        return new ResolvedUpload(
                session.getId(),
                session.getObjectKey(),
                storageService.getPublicUrl(session.getObjectKey()),
                session.getFileSize(),
                session.getContentType());
    }

    private void verifyUpload(
            UploadSession session,
            Long ownerId,
            LocalDateTime now) {
        if (session == null) {
            throw UPLOAD_SESSION_NOT_FOUND.toException();
        }
        session.validateUploadCompletion(ownerId, now);
        StoredObjectMetadata metadata = storageService.findMetadata(session.getObjectKey())
                .orElseThrow(UPLOAD_FILE_NOT_FOUND::toException);
        session.verifyUploaded(
                ownerId,
                metadata.contentLength(),
                metadata.contentType(),
                now);
    }

    private Map<Long, UploadSession> findSessions(List<Long> uploadIds) {
        Map<Long, UploadSession> sessions = new LinkedHashMap<>();
        uploadSessionRepository.findAllById(uploadIds)
                .forEach(session -> sessions.put(session.getId(), session));
        if (sessions.size() != uploadIds.size()) {
            throw UPLOAD_SESSION_NOT_FOUND.toException();
        }
        return sessions;
    }

    private void validateUniqueIds(List<Long> uploadIds) {
        if (uploadIds.stream().anyMatch(uploadId -> uploadId == null || uploadId <= 0)
                || new HashSet<>(uploadIds).size() != uploadIds.size()) {
            throw UPLOAD_SESSION_INVALID.toException();
        }
    }

    private String normalizeFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            throw UPLOAD_SESSION_INVALID.toException();
        }
        String normalized = folder.trim();
        boolean hasParentTraversal = Arrays.asList(normalized.split("/")).contains("..");
        if (normalized.startsWith("/") || normalized.endsWith("/") || hasParentTraversal) {
            throw UPLOAD_SESSION_INVALID.toException();
        }
        return normalized;
    }
}
