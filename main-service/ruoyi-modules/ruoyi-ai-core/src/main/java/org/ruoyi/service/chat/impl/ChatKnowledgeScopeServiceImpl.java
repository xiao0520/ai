package org.ruoyi.service.chat.impl;

import lombok.RequiredArgsConstructor;
import org.ruoyi.domain.dto.knowledge.rag.RetrievalProfile;
import org.ruoyi.service.chat.IChatKnowledgeScopeService;
import org.ruoyi.service.knowledge.IKnowledgeRetrievalScopeService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 默认聊天知识库范围解析服务。 */
@Service
@RequiredArgsConstructor
public class ChatKnowledgeScopeServiceImpl implements IChatKnowledgeScopeService {

    private final IKnowledgeRetrievalScopeService retrievalScopeService;

    @Override
    public List<Long> resolve(Long requestedKnowledgeId, RetrievalProfile retrievalProfile) {
        if (requestedKnowledgeId == null) {
            return List.of();
        }
        // 先给出明确的就绪提示；create() 仍会重新执行用户 ACL 和发布状态校验。
        if (!retrievalScopeService.isReady(requestedKnowledgeId, retrievalProfile)) {
            throw new org.ruoyi.common.core.exception.ServiceException("所选知识库索引尚未就绪");
        }
        return List.of(requestedKnowledgeId);
    }
}
