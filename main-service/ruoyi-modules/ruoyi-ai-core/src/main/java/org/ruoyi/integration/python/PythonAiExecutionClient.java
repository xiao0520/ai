package org.ruoyi.integration.python;

import org.ruoyi.domain.dto.chat.PythonChatRouteRequest;
import org.ruoyi.domain.dto.chat.PythonChatRouteResponse;
import org.ruoyi.domain.dto.chat.PythonChatStreamEvent;
import org.ruoyi.domain.dto.chat.PythonChatStreamRequest;
import org.ruoyi.domain.dto.contract.ContractReviewExecutionRequest;
import org.ruoyi.domain.dto.knowledge.rag.RagChatStreamRequest;

import java.util.function.Consumer;

/**
 * Python AI 执行服务的内部调用接口。
 *
 * Java 负责鉴权、预算和持久化，Python 只执行已核定的路由、普通聊天或 RAG 请求。
 */
public interface PythonAiExecutionClient {

    /**
     * 在 Java 下发的候选快照范围内选择聊天模型。
     *
     * @param request 模型候选、请求特征及路由策略
     * @return Python 选择的模型及命中原因
     */
    PythonChatRouteResponse routeChat(PythonChatRouteRequest request);

    /**
     * 执行普通聊天并逐个回传标准流事件。
     *
     * @param request       已移除真实凭证的聊天执行请求
     * @param eventConsumer 流事件消费者
     */
    void streamChat(PythonChatStreamRequest request, Consumer<PythonChatStreamEvent> eventConsumer);

    /**
     * 执行带授权检索范围的 RAG 聊天并逐个回传标准流事件。
     *
     * @param request       RAG 聊天执行请求
     * @param eventConsumer 流事件消费者
     */
    void streamRagChat(RagChatStreamRequest request, Consumer<PythonChatStreamEvent> eventConsumer);

    /** 执行合同审查，文件只随本次已签名的内部请求传递。 */
    void streamContractReview(ContractReviewExecutionRequest request, byte[] fileBytes,
                              String mediaType,
                              Consumer<PythonChatStreamEvent> eventConsumer);
}
