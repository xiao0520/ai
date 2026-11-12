package org.ruoyi.controller.internal;

import jakarta.servlet.http.HttpServletRequest;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.domain.dto.knowledge.rag.InternalError;
import org.ruoyi.service.knowledge.KnowledgeArtifactAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 内部资源接口只返回稳定错误码，不回传异常、签名、票据或存储信息。 */
@RestControllerAdvice(assignableTypes = {InternalKnowledgeArtifactController.class, InternalKnowledgeScopeController.class})
@ConditionalOnProperty(prefix = "knowledge.worker-auth", name = "enabled", havingValue = "true")
public class InternalKnowledgeArtifactErrorHandler {

    @ExceptionHandler(KnowledgeArtifactAccessException.class)
    public ResponseEntity<InternalError> handleAccess(KnowledgeArtifactAccessException error,
                                                      HttpServletRequest request) {
        return ResponseEntity.status(error.status())
            .body(new InternalError(requestId(request), error.errorCode(), error.retryable()));
    }

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<InternalError> handleAuthentication(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new InternalError(requestId(request), InternalError.ErrorCode.V_AUTHORIZATION, false));
    }

    @ExceptionHandler({IllegalArgumentException.class, java.io.IOException.class})
    public ResponseEntity<InternalError> handleValidation(HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity()
            .body(new InternalError(requestId(request), InternalError.ErrorCode.V_VALIDATION, false));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<InternalError> handleUnexpected(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new InternalError(requestId(request), InternalError.ErrorCode.V_INTERNAL, true));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(InternalKnowledgeArtifactController.REQUEST_ID_ATTRIBUTE);
        if (value instanceof String id && !id.isBlank()) return id;
        value = request.getAttribute(InternalKnowledgeScopeController.REQUEST_ID_ATTRIBUTE);
        return value instanceof String id && !id.isBlank() ? id : "unknown";
    }
}
