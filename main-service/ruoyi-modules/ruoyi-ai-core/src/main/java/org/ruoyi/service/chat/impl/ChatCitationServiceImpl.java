package org.ruoyi.service.chat.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.domain.entity.chat.ChatMessageCitation;
import org.ruoyi.domain.vo.chat.ChatCitationVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeManifestMemberRow;
import org.ruoyi.mapper.chat.ChatMessageCitationMapper;
import org.ruoyi.mapper.knowledge.KnowledgeArtifactMapper;
import org.ruoyi.service.chat.IChatCitationService;
import org.ruoyi.service.knowledge.IKnowledgeAclService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 回答引用查询服务。
 *
 * 列表阶段只返回已经随消息持久化的摘要；用户回访单个引用时，再校验消息归属、当前知识库 ACL
 * 和当前发布清单，权限撤回或文档换版后不继续暴露旧来源
 */
@Service
@RequiredArgsConstructor
public class ChatCitationServiceImpl implements IChatCitationService {

    private final ChatMessageCitationMapper citationMapper;
    private final KnowledgeArtifactMapper artifactMapper;
    private final IKnowledgeAclService aclService;
    private final ObjectMapper objectMapper;

    @Override
    public Map<Long, List<ChatCitationVo>> queryByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<ChatCitationVo>> mutableGroups = new LinkedHashMap<>();
        for (ChatMessageCitation citation : citationMapper.selectByMessageIds(messageIds)) {
            mutableGroups.computeIfAbsent(citation.getMessageId(), ignored -> new ArrayList<>())
                .add(toVo(citation));
        }

        Map<Long, List<ChatCitationVo>> immutableGroups = new LinkedHashMap<>();
        mutableGroups.forEach((messageId, values) ->
            immutableGroups.put(messageId, List.copyOf(values)));
        return Map.copyOf(immutableGroups);
    }

    @Override
    public ChatCitationVo queryMyCitation(Long userId, Long messageId, String citationId) {
        // 先用消息归属约束查询，避免仅凭 citationId 枚举其他用户引用。
        ChatMessageCitation citation = citationMapper.selectOwnedCitation(messageId, citationId, userId);
        if (citation == null) {
            throw new ServiceException("引用不存在或无权访问");
        }
        if (!aclService.canAccess(
            userId, citation.getKnowledgeId(), AiDomainConstants.PERMISSION_READ)) {
            throw new ServiceException("当前已无权访问该引用来源");
        }

        // ACL 通过后仍需确认引用对应的文档 generation 属于当前生效发布清单。
        List<KnowledgeManifestMemberRow> validMembers = artifactMapper.selectValidMembers(
            citation.getIndexVersionId(), List.of(citation.getGenerationId()),
            SystemConstants.NORMAL, SystemConstants.NORMAL, AiDomainConstants.STATUS_ACTIVE,
            AiDomainConstants.KNOWLEDGE_DOCUMENT_STATUS_READY,
            AiDomainConstants.KNOWLEDGE_DOCUMENT_STATUS_READY,
            AiDomainConstants.STATUS_READY
        );
        boolean current = validMembers.stream().anyMatch(item ->
            citation.getDocumentId().equals(item.getDocumentId())
                && citation.getDocumentVersionId().equals(item.getDocumentVersionId())
                && citation.getGenerationId().equals(item.getGenerationId()));
        if (!current) {
            throw new ServiceException("引用来源已失效或被新版本替换");
        }
        return toVo(citation);
    }

    /** 将数据库中的 JSON 定位信息还原为前端可消费的强类型引用对象。 */
    private ChatCitationVo toVo(ChatMessageCitation citation) {
        try {
            ChatCitationVo value = new ChatCitationVo();
            value.setMessageId(citation.getMessageId());
            value.setCitationId(citation.getCitationId());
            value.setTitle(citation.getTitle());
            value.setTitlePath(objectMapper.readValue(
                citation.getTitlePathJson(), new TypeReference<List<String>>() { }));
            value.setLocator(objectMapper.readTree(citation.getLocatorJson()));
            value.setExcerpt(citation.getExcerpt());
            return value;
        } catch (JsonProcessingException error) {
            throw new ServiceException("引用位置数据损坏，请联系管理员处理");
        }
    }
}
