package org.ruoyi.service.chat;

import org.ruoyi.domain.dto.knowledge.rag.RetrievalProfile;

import java.util.List;

/**
 * 解析聊天请求本轮应检索的知识库范围。
 */
public interface IChatKnowledgeScopeService {

    /**
     * 校验并返回请求明确指定的知识库；未指定时返回空集合进入普通聊天。
     *
     * @param requestedKnowledgeId 请求指定的知识库主键，可为空
     * @param retrievalProfile 本轮检索配置，用于核验索引和重排清单是否就绪
     * @return 本轮检索的知识库主键；空集合表示普通聊天
     */
    List<Long> resolve(Long requestedKnowledgeId, RetrievalProfile retrievalProfile);
}
