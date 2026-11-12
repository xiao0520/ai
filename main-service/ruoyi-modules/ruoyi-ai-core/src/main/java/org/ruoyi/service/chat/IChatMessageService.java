package org.ruoyi.service.chat;

import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.chat.ChatMessageBo;
import org.ruoyi.domain.vo.chat.ChatMessageVo;

import java.util.List;

/**
 * 聊天消息Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IChatMessageService {

    /**
     * 查询聊天消息
     *
     * @param id 主键
     * @return 聊天消息
     */
    ChatMessageVo queryById(Long id);

    /**
     * 分页查询聊天消息列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 聊天消息分页列表
     */
    TableDataInfo<ChatMessageVo> queryPageList(ChatMessageBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的聊天消息列表
     *
     * @param bo 查询条件
     * @return 聊天消息列表
     */
    List<ChatMessageVo> queryList(ChatMessageBo bo);

}

