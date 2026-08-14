package sena.core.content.upload.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sena.core.content.upload.enums.UploadPurpose;
import sena.core.content.upload.enums.UploadStatus;
import sena.core.global.base.BaseEntity;

import java.time.LocalDateTime;

import static sena.core.global.exception.ErrorCode.*;

@Entity
@Table(name = "upload_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "upload_session_id")
    private Long id;

    @Column(nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private UploadPurpose purpose;

    @Column(nullable = false, unique = true, length = 500)
    private String objectKey;

    @Column(nullable = false)
    private long fileSize;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UploadStatus status;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private Long attachedTargetId;

    private LocalDateTime attachedAt;

    @Version
    private long version;

    private UploadSession(
            Long ownerId,
            UploadPurpose purpose,
            String objectKey,
            long fileSize,
            String contentType,
            LocalDateTime expiresAt) {
        changeOwnerId(ownerId);
        changePurpose(purpose);
        changeObjectKey(objectKey);
        changeFileSize(fileSize);
        changeContentType(contentType);
        changeExpiresAt(expiresAt);
        this.status = UploadStatus.PENDING;
    }

    public static UploadSession create(
            Long ownerId,
            UploadPurpose purpose,
            String objectKey,
            long fileSize,
            String contentType,
            LocalDateTime expiresAt) {
        return new UploadSession(
                ownerId,
                purpose,
                objectKey,
                fileSize,
                contentType,
                expiresAt);
    }

    public void verifyUploaded(
            Long ownerId,
            long fileSize,
            String contentType,
            LocalDateTime now) {
        validateUploadCompletion(ownerId, now);
        if (this.fileSize != fileSize
                || contentType == null
                || !this.contentType.equalsIgnoreCase(contentType)) {
            throw UPLOAD_FILE_MISMATCH.toException();
        }
        this.status = UploadStatus.UPLOADED;
    }

    public void validateUploadCompletion(Long ownerId, LocalDateTime now) {
        if (!this.ownerId.equals(ownerId)) {
            throw UPLOAD_SESSION_FORBIDDEN.toException();
        }
        if (status != UploadStatus.PENDING && status != UploadStatus.UPLOADED) {
            throw UPLOAD_SESSION_ALREADY_USED.toException();
        }
        if (now == null || !expiresAt.isAfter(now)) {
            throw UPLOAD_SESSION_EXPIRED.toException();
        }
    }

    public void validateAttachable(Long ownerId, UploadPurpose purpose, LocalDateTime now) {
        if (!this.ownerId.equals(ownerId)) {
            throw UPLOAD_SESSION_FORBIDDEN.toException();
        }
        if (this.purpose != purpose) {
            throw UPLOAD_SESSION_INVALID.toException();
        }
        if (status != UploadStatus.UPLOADED) {
            throw UPLOAD_SESSION_ALREADY_USED.toException();
        }
        if (now == null || !expiresAt.isAfter(now)) {
            throw UPLOAD_SESSION_EXPIRED.toException();
        }
    }

    public void attach(Long targetId, LocalDateTime now) {
        if (targetId == null || targetId <= 0 || now == null || status != UploadStatus.UPLOADED) {
            throw UPLOAD_SESSION_INVALID.toException();
        }
        this.status = UploadStatus.ATTACHED;
        this.attachedTargetId = targetId;
        this.attachedAt = now;
    }

    public void expire() {
        if (status == UploadStatus.PENDING || status == UploadStatus.UPLOADED) {
            this.status = UploadStatus.EXPIRED;
        }
    }

    public void markDeleted() {
        this.status = UploadStatus.DELETED;
    }

    private void changeOwnerId(Long ownerId) {
        if (ownerId == null || ownerId <= 0) {
            throw UPLOAD_SESSION_INVALID.toException();
        }
        this.ownerId = ownerId;
    }

    private void changePurpose(UploadPurpose purpose) {
        if (purpose == null) {
            throw UPLOAD_SESSION_INVALID.toException();
        }
        this.purpose = purpose;
    }

    private void changeObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.length() > 500) {
            throw UPLOAD_SESSION_INVALID.toException();
        }
        this.objectKey = objectKey.trim();
    }

    private void changeFileSize(long fileSize) {
        if (fileSize <= 0) {
            throw UPLOAD_SESSION_INVALID.toException();
        }
        this.fileSize = fileSize;
    }

    private void changeContentType(String contentType) {
        if (contentType == null || contentType.isBlank() || contentType.length() > 100) {
            throw UPLOAD_SESSION_INVALID.toException();
        }
        this.contentType = contentType.trim();
    }

    private void changeExpiresAt(LocalDateTime expiresAt) {
        if (expiresAt == null) {
            throw UPLOAD_SESSION_INVALID.toException();
        }
        this.expiresAt = expiresAt;
    }
}
