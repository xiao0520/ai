package org.ruoyi.service.chat;

import org.ruoyi.domain.vo.chat.ChatCitationVo;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * RAG 消息引用的展示与回访鉴权能力。
 *
 * <p>引用只有在当前用户仍具备知识库权限且来源仍属于当前发布清单时才可展示或访问。</p>
 */
public interface IChatCitationService {

    /**
     * 批量查询消息的引用摘要，用于会话消息列表展示。
     *
     * @param messageIds 已通过会话归属校验的消息 ID 集合
     * @return 按消息 ID 分组的引用摘要，不存在引用时返回空集合
     */
    Map<Long, List<ChatCitationVo>> queryByMessageIds(Collection<Long> messageIds);

    /**
     * 查询单个引用详情，并按当前 ACL 和当前发布清单重新鉴权。
     *
     * @param userId     当前用户 ID
     * @param messageId  助手消息 ID
     * @param citationId 引用标识
     * @return 当前仍允许访问的引用详情
     */
    ChatCitationVo queryMyCitation(Long userId, Long messageId, String citationId);
}