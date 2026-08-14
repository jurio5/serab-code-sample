package sena.core.content.upload.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sena.core.content.member.domain.Member;
import sena.core.content.upload.dto.UploadCompletionRequest;
import sena.core.global.request.RequestContext;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static sena.core.testUtil.fixtures.MemberFixture.member;

@ExtendWith(MockitoExtension.class)
class UploadCompletionServiceTest {

    @Mock
    private UploadService uploadService;

    @Mock
    private RequestContext requestContext;

    @InjectMocks
    private UploadCompletionService uploadCompletionService;

    @Test
    @DisplayName("직접 업로드 완료")
    void completeUploads() {
        Member actor = member(1L, "test@test.com", "테스터");
        UploadCompletionRequest request = new UploadCompletionRequest(List.of(10L, 20L));
        given(requestContext.getActor()).willReturn(actor);

        uploadCompletionService.completeUploads(request);

        verify(uploadService).verifyUploads(1L, List.of(10L, 20L));
    }
}
