package sena.core.content.upload.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import sena.core.content.member.domain.Member;
import sena.core.content.upload.dto.UploadCompletionRequest;
import sena.core.content.upload.vo.UploadIds;
import sena.core.global.request.RequestContext;

@Service
@RequiredArgsConstructor
public class UploadCompletionService {

    private final UploadService uploadService;
    private final RequestContext requestContext;

    public void completeUploads(UploadCompletionRequest request) {
        UploadIds vo = UploadIds.required(request == null ? null : request.uploadIds());
        Member actor = requestContext.getActor();

        uploadService.verifyUploads(actor.getId(), vo.values());
    }
}
