package sena.core.content.upload.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import sena.core.content.upload.domain.UploadSession;
import sena.core.content.upload.enums.UploadPurpose;
import sena.core.content.upload.enums.UploadStatus;
import sena.core.content.upload.monitoring.UploadLifecycleMetrics;
import sena.core.content.upload.repository.UploadSessionRepository;
import sena.core.content.upload.vo.PrepareUpload;
import sena.core.content.upload.vo.PreparedUpload;
import sena.core.content.upload.vo.ResolvedUpload;
import sena.core.content.upload.vo.UploadAttachment;
import sena.core.global.exception.ServiceException;
import sena.core.global.storage.StoragePresignedUpload;
import sena.core.global.storage.StorageService;
import sena.core.global.storage.StoredObjectMetadata;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static sena.core.global.exception.ErrorCode.UPLOAD_FILE_MISMATCH;
import static sena.core.global.exception.ErrorCode.UPLOAD_SESSION_FORBIDDEN;
import static sena.core.global.exception.ErrorCode.UPLOAD_SESSION_INVALID;

@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    @Mock
    private UploadSessionRepository uploadSessionRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private UploadLifecycleMetrics uploadLifecycleMetrics;

    @InjectMocks
    private UploadService uploadService;

    @Test
    @DisplayName("Presigned 업로드 준비")
    void prepareUploads() {
        given(uploadSessionRepository.save(any(UploadSession.class))).willAnswer(invocation -> {
            UploadSession session = invocation.getArgument(0);
            ReflectionTestUtils.setField(session, "id", 1L);
            return session;
        });
        given(storageService.createPresignedUpload(
                anyString(),
                eq("image/png"),
                eq(Duration.ofMinutes(10))))
                .willReturn(new StoragePresignedUpload(
                        "https://upload.example.com",
                        Map.of("content-type", "image/png")));
        given(storageService.getPublicUrl(anyString()))
                .willReturn("https://cdn.example.com/community/posts/image.png");

        List<PreparedUpload> result = uploadService.prepareUploads(
                1L,
                UploadPurpose.COMMUNITY_POST_IMAGE,
                "community/posts",
                List.of(PrepareUpload.of(10, "image/png", "png")));

        assertEquals(1, result.size());
        assertEquals(1L, result.getFirst().uploadId());
        assertEquals("https://upload.example.com", result.getFirst().uploadUrl());
        verify(uploadLifecycleMetrics).recordPrepared(UploadPurpose.COMMUNITY_POST_IMAGE, 1);
    }

    @Test
    @DisplayName("업로드 파일 검증 완료")
    void verifyUploads() {
        UploadSession session = uploadSession(1L, 10, "image/png");
        given(uploadSessionRepository.findAllById(List.of(1L))).willReturn(List.of(session));
        given(storageService.findMetadata("community/posts/image.png"))
                .willReturn(Optional.of(new StoredObjectMetadata(10, "image/png")));

        uploadService.verifyUploads(1L, List.of(1L));

        assertEquals(UploadStatus.UPLOADED, session.getStatus());
        verify(uploadLifecycleMetrics).recordCompleted(any());
    }

    @Test
    @DisplayName("다른 사용자의 업로드 완료 차단")
    void verifyUploads_Fail_OwnerMismatch() {
        UploadSession session = uploadSession(1L, 10, "image/png");
        given(uploadSessionRepository.findAllById(List.of(1L))).willReturn(List.of(session));

        ServiceException exception = assertThrows(
                ServiceException.class,
                () -> uploadService.verifyUploads(2L, List.of(1L)));

        assertEquals(UPLOAD_SESSION_FORBIDDEN, exception.getErrorCode());
        verifyNoInteractions(storageService);
    }

    @Test
    @DisplayName("업로드 파일 확인")
    void resolveAttachableUploads() {
        UploadSession session = uploadSession(1L, 10, "image/png");
        session.verifyUploaded(1L, 10, "image/png", LocalDateTime.now());
        given(uploadSessionRepository.findAllById(List.of(1L))).willReturn(List.of(session));
        given(storageService.getPublicUrl("community/posts/image.png"))
                .willReturn("https://cdn.example.com/community/posts/image.png");

        List<ResolvedUpload> result = uploadService.resolveAttachableUploads(
                1L,
                UploadPurpose.COMMUNITY_POST_IMAGE,
                List.of(1L));

        assertEquals(1, result.size());
        assertEquals("community/posts/image.png", result.getFirst().objectKey());
        assertEquals(10, result.getFirst().fileSize());
    }

    @Test
    @DisplayName("업로드 파일 정보 불일치 차단")
    void verifyUploads_Fail_MetadataMismatch() {
        UploadSession session = uploadSession(1L, 10, "image/png");
        given(uploadSessionRepository.findAllById(List.of(1L))).willReturn(List.of(session));
        given(storageService.findMetadata("community/posts/image.png"))
                .willReturn(Optional.of(new StoredObjectMetadata(20, "image/png")));

        ServiceException exception = assertThrows(
                ServiceException.class,
                () -> uploadService.verifyUploads(1L, List.of(1L)));

        assertEquals(UPLOAD_FILE_MISMATCH, exception.getErrorCode());
    }

    @Test
    @DisplayName("업로드 세션 연결 완료")
    void attachUploads() {
        UploadSession session = uploadSession(1L, 10, "image/png");
        session.verifyUploaded(1L, 10, "image/png", LocalDateTime.now());
        given(uploadSessionRepository.findAllById(List.of(1L))).willReturn(List.of(session));

        uploadService.attachUploads(
                1L,
                UploadPurpose.COMMUNITY_POST_IMAGE,
                List.of(UploadAttachment.of(1L, 100L)));

        assertEquals(UploadStatus.ATTACHED, session.getStatus());
        assertEquals(100L, session.getAttachedTargetId());
        verify(uploadLifecycleMetrics).recordAttached(UploadPurpose.COMMUNITY_POST_IMAGE, 1);
    }

    @Test
    @DisplayName("업로드 객체 삭제 상태 반영")
    void markDeletedByObjectKey() {
        UploadSession session = uploadSession(1L, 10, "image/png");
        given(uploadSessionRepository.findByObjectKey("community/posts/image.png"))
                .willReturn(Optional.of(session));

        uploadService.markDeletedByObjectKey("community/posts/image.png");

        assertEquals(UploadStatus.DELETED, session.getStatus());
        verify(uploadSessionRepository).findByObjectKey("community/posts/image.png");
    }

    @Test
    @DisplayName("연결된 업로드 삭제")
    void deleteAttachedUploads() {
        UploadSession session = uploadSession(1L, 10, "image/png");
        session.verifyUploaded(1L, 10, "image/png", LocalDateTime.now());
        session.attach(100L, LocalDateTime.now());
        given(uploadSessionRepository.findByPurposeAndAttachedTargetIdAndStatus(
                UploadPurpose.COMMUNITY_POST_IMAGE,
                100L,
                UploadStatus.ATTACHED)).willReturn(List.of(session));

        boolean deleted = uploadService.deleteAttachedUploads(
                UploadPurpose.COMMUNITY_POST_IMAGE,
                100L);

        assertTrue(deleted);
        assertEquals(UploadStatus.DELETED, session.getStatus());
        verify(storageService).delete("community/posts/image.png");
    }

    @Test
    @DisplayName("업로드 폴더의 상위 경로 접근 차단")
    void prepareUploads_Fail_ParentTraversal() {
        ServiceException exception = assertThrows(
                ServiceException.class,
                () -> uploadService.prepareUploads(
                        1L,
                        UploadPurpose.COMMUNITY_POST_IMAGE,
                        "community/../posts",
                        List.of(PrepareUpload.of(10, "image/png", "png"))));

        assertEquals(UPLOAD_SESSION_INVALID, exception.getErrorCode());
    }

    private UploadSession uploadSession(Long id, long fileSize, String contentType) {
        UploadSession session = UploadSession.create(
                1L,
                UploadPurpose.COMMUNITY_POST_IMAGE,
                "community/posts/image.png",
                fileSize,
                contentType,
                LocalDateTime.now().plusHours(1));
        ReflectionTestUtils.setField(session, "id", id);
        return session;
    }
}
