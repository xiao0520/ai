package org.ruoyi.controller.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.ruoyi.domain.dto.knowledge.RagContractJson;
import org.ruoyi.domain.dto.knowledge.rag.ArtifactTicketRequest;
import org.ruoyi.domain.dto.knowledge.rag.ArtifactTicketResponse;
import org.ruoyi.domain.dto.knowledge.rag.ArtifactUploadResponse;
import org.ruoyi.domain.dto.knowledge.rag.InternalError;
import org.ruoyi.integration.security.InternalRequestVerifier;
import org.ruoyi.service.knowledge.KnowledgeArtifactAccessException;
import org.ruoyi.service.knowledge.KnowledgeArtifactAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** Python Worker 访问 Java 绑定源文件与产物清单的内部接口，不接受浏览器登录身份。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/knowledge/jobs")
@ConditionalOnProperty(prefix = "knowledge.worker-auth", name = "enabled", havingValue = "true")
public class InternalKnowledgeArtifactController {
    public static final String REQUEST_ID_ATTRIBUTE =
        InternalKnowledgeArtifactController.class.getName() + ".requestId";
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final String BEARER_PREFIX = "Bearer ";
    private final InternalRequestVerifier verifier;
    private final RagContractJson codec;
    private final KnowledgeArtifactAccessService artifacts;

    /** 对原始 JSON 验签后签发当前任务对象的短期能力票据。 */
    @PostMapping(path = "/{id}/artifact-tickets", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ArtifactTicketResponse issue(@PathVariable Long id, @RequestBody byte[] body,
                                        @RequestHeader(value = "X-AI-Service-Token", required = false) String token,
                                        @RequestHeader(value = "X-AI-Signature-Version", required = false) String version,
                                        @RequestHeader(value = "X-AI-Timestamp", required = false) String timestamp,
                                        @RequestHeader(value = "X-AI-Nonce", required = false) String nonce,
                                        @RequestHeader(value = "X-AI-Signature", required = false) String signature,
                                        HttpServletRequest servletRequest) throws IOException {
        if (body.length == 0 || body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("VALIDATION");
        }
        String query = servletRequest.getQueryString() == null ? "" : servletRequest.getQueryString();
        verifier.verify(servletRequest.getMethod(), servletRequest.getRequestURI(), query, body,
            token, version, timestamp, nonce, signature);
        ArtifactTicketRequest request = codec.read(body, ArtifactTicketRequest.class);
        servletRequest.setAttribute(REQUEST_ID_ATTRIBUTE, request.requestId());
        return artifacts.issue(id, request);
    }

    /** Bearer 能力只允许读取票据绑定的当前源对象。 */
    @GetMapping("/{id}/artifacts/{objectId}")
    public void download(@PathVariable Long id, @PathVariable Long objectId,
                         @RequestHeader(value = "Authorization", required = false) String authorization,
                         HttpServletResponse response) throws IOException {
        artifacts.download(id, objectId, bearer(authorization), response);
    }

    /** 清单使用单用途 Bearer 票据上传，并由服务层复核 attempt、generation 与模型快照。 */
    @PutMapping(path = "/{id}/manifests/{generationId}", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ArtifactUploadResponse uploadManifest(
        @PathVariable Long id,
        @PathVariable Long generationId,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody byte[] body
    ) {
        return artifacts.uploadManifest(id, generationId, bearer(authorization), body);
    }

    private String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)
            || authorization.length() <= BEARER_PREFIX.length()) {
            throw new KnowledgeArtifactAccessException(HttpStatus.FORBIDDEN,
                InternalError.ErrorCode.V_AUTHORIZATION, false);
        }
        return authorization.substring(BEARER_PREFIX.length());
    }
}
