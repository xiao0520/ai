package org.ruoyi.service.knowledge;

import org.ruoyi.domain.dto.knowledge.rag.InternalError;
import org.springframework.http.HttpStatus;

/**
 * 受控产物访问边界使用的稳定业务异常。
 *
 * <p>异常只向内部 Worker 暴露 HTTP 状态、机器错误码和可重试标识，避免泄露任务及 OSS 细节。</p>
 */
public class KnowledgeArtifactAccessException extends RuntimeException {

    private final HttpStatus status;
    private final InternalError.ErrorCode errorCode;
    private final boolean retryable;

    /**
     * 创建受控产物访问异常。
     *
     * @param status 对外返回的 HTTP 状态
     * @param errorCode 稳定机器错误码
     * @param retryable Worker 是否可以重试
     */
    public KnowledgeArtifactAccessException(HttpStatus status, InternalError.ErrorCode errorCode,
                                            boolean retryable) {
        super(errorCode.value());
        this.status = status;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /**
     * @return 对外返回的 HTTP 状态
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * @return 稳定机器错误码
     */
    public InternalError.ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * @return Worker 是否可以重试
     */
    public boolean retryable() {
        return retryable;
    }
}