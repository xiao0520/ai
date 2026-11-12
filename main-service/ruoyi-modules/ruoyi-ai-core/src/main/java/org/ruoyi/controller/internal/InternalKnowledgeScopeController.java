package org.ruoyi.controller.internal;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.ruoyi.domain.dto.knowledge.RagContractJson;
import org.ruoyi.domain.dto.knowledge.rag.CandidateValidationResponse;
import org.ruoyi.domain.dto.knowledge.rag.ManifestPageRequest;
import org.ruoyi.domain.dto.knowledge.rag.ManifestPageResponse;
import org.ruoyi.domain.dto.knowledge.rag.ValidateCandidatesRequest;
import org.ruoyi.integration.security.InternalRequestVerifier;
import org.ruoyi.service.knowledge.IKnowledgeRetrievalScopeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** Python 仅能读取 Java 已为当前业务请求固化的授权范围并复核候选。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/knowledge/scopes")
@ConditionalOnProperty(prefix = "knowledge.worker-auth", name = "enabled", havingValue = "true")
public class InternalKnowledgeScopeController {
    public static final String REQUEST_ID_ATTRIBUTE =
        InternalKnowledgeScopeController.class.getName() + ".requestId";
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private final InternalRequestVerifier verifier;
    private final RagContractJson codec;
    private final IKnowledgeRetrievalScopeService scopes;

    /** 分页返回与快照、索引和 manifestRevision 绑定的已发布成员。 */
    @PostMapping(path = "/{snapshotId}/manifest-pages", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ManifestPageResponse pageManifest(
        @PathVariable String snapshotId,
        @RequestBody byte[] body,
        @RequestHeader(value = "X-AI-Service-Token", required = false) String token,
        @RequestHeader(value = "X-AI-Signature-Version", required = false) String version,
        @RequestHeader(value = "X-AI-Timestamp", required = false) String timestamp,
        @RequestHeader(value = "X-AI-Nonce", required = false) String nonce,
        @RequestHeader(value = "X-AI-Signature", required = false) String signature,
        HttpServletRequest servletRequest
    ) throws IOException {
        verify(body, token, version, timestamp, nonce, signature, servletRequest);
        ManifestPageRequest request = codec.read(body, ManifestPageRequest.class);
        servletRequest.setAttribute(REQUEST_ID_ATTRIBUTE, request.requestId());
        return scopes.pageManifest(snapshotId, request);
    }

    /** 当前权限、文档状态和索引成员均有效时才签发候选验证票据。 */
    @PostMapping(path = "/{snapshotId}/validate-candidates",
        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CandidateValidationResponse validateCandidates(
        @PathVariable String snapshotId,
        @RequestBody byte[] body,
        @RequestHeader(value = "X-AI-Service-Token", required = false) String token,
        @RequestHeader(value = "X-AI-Signature-Version", required = false) String version,
        @RequestHeader(value = "X-AI-Timestamp", required = false) String timestamp,
        @RequestHeader(value = "X-AI-Nonce", required = false) String nonce,
        @RequestHeader(value = "X-AI-Signature", required = false) String signature,
        HttpServletRequest servletRequest
    ) throws IOException {
        verify(body, token, version, timestamp, nonce, signature, servletRequest);
        ValidateCandidatesRequest request = codec.read(body, ValidateCandidatesRequest.class);
        servletRequest.setAttribute(REQUEST_ID_ATTRIBUTE, request.requestId());
        return scopes.validateCandidates(snapshotId, request);
    }

    private void verify(byte[] body, String token, String version, String timestamp,
                        String nonce, String signature, HttpServletRequest request) {
        if (body.length == 0 || body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("VALIDATION");
        }
        String query = request.getQueryString() == null ? "" : request.getQueryString();
        verifier.verify(request.getMethod(), request.getRequestURI(), query, body,
            token, version, timestamp, nonce, signature);
    }
}
