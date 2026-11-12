package org.ruoyi.mapper.chat;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.domain.bo.chat.ChatSessionBo;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.chat.ChatSession;
import org.ruoyi.domain.vo.chat.ChatSessionVo;

import java.util.List;
import java.util.Date;

/**
 * 会话管理Mapper接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface ChatSessionMapper extends BaseMapperPlus<ChatSession, ChatSessionVo> {

    /** 按管理端筛选条件查询会话；传入分页对象时由分页插件处理分页。 */
    List<ChatSessionVo> selectSessionList(@Param("page") Page<ChatSessionVo> page, @Param("bo") ChatSessionBo bo);

    int updateSessionTitle(@Param("id") Long id, @Param("sessionTitle") String sessionTitle,
                           @Param("updateTime") Date updateTime, @Param("ownerUserId") Long ownerUserId);

    int updateStatus(@Param("id") Long id, @Param("status") String status,
                     @Param("updateTime") Date updateTime, @Param("ownerUserId") Long ownerUserId);

    /** 锁定读取单个会话，用于短事务内安全追加消息。 */
    ChatSession selectByIdForUpdate(@Param("id") Long id);

    /** 追加用户消息后更新会话摘要。 */
    int recordUserTurn(@Param("id") Long id, @Param("userId") Long userId, @Param("agentId") Long agentId,
                       @Param("modelId") Long modelId, @Param("updateTime") Date updateTime);

    /** 追加助手消息后累计本次真实 Token。 */
    int recordAssistantTurn(@Param("id") Long id, @Param("userId") Long userId, @Param("modelId") Long modelId,
                            @Param("inputTokens") long inputTokens, @Param("outputTokens") long outputTokens,
                            @Param("deductTokens") long deductTokens, @Param("updateTime") Date updateTime);
}

