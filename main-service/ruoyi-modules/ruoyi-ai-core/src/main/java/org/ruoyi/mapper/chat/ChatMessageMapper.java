package org.ruoyi.mapper.chat;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.domain.bo.chat.ChatMessageBo;
import org.ruoyi.domain.entity.chat.ChatMessage;
import org.ruoyi.domain.vo.chat.ChatMessageVo;

import java.util.List;

/**
 * 聊天消息Mapper接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface ChatMessageMapper extends BaseMapperPlus<ChatMessage, ChatMessageVo> {

    /** 按消息审计筛选条件查询消息；传入分页对象时由分页插件处理分页。 */
    List<ChatMessageVo> selectMessageList(@Param("page") Page<ChatMessageVo> page, @Param("bo") ChatMessageBo bo);

    List<ChatMessage> selectRecentHistory(@Param("sessionId") Long sessionId,
                                           @Param("coveredMessageSeq") int coveredMessageSeq,
                                           @Param("requestId") String requestId,
                                           @Param("userRole") String userRole,
                                           @Param("assistantRole") String assistantRole,
                                           @Param("failedStatus") String failedStatus,
                                           @Param("limit") int limit);
}
