package org.ruoyi.service.chat;

import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.domain.bo.chat.ChatSessionBo;
import org.ruoyi.domain.vo.chat.ChatSessionVo;

import java.util.List;

/**
 * 会话管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IChatSessionService {

    /**
     * 查询会话管理
     *
     * @param id 主键
     * @return 会话管理
     */
    ChatSessionVo queryById(Long id);

    /**
     * 分页查询会话管理列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 会话管理分页列表
     */
    TableDataInfo<ChatSessionVo> queryPageList(ChatSessionBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的会话管理列表
     *
     * @param bo 查询条件
     * @return 会话管理列表
     */
    List<ChatSessionVo> queryList(ChatSessionBo bo);

    /**
     * 查询当前登录用户可访问的会话详情。
     *
     * 管理员可查看任意会话；普通用户仅可查看本人会话。
     *
     * @param id 会话ID
     * @return 会话详情
     */
    ChatSessionVo queryAccessibleById(Long id);

    /**
     * 查询当前登录用户本人会话详情。
     *
     * @param id 会话ID
     * @return 本人会话详情
     */
    ChatSessionVo queryMyById(Long id);

    /**
     * 创建当前用户的新会话。
     *
     * @param userId       当前用户主键
     * @param sessionTitle 会话标题
     * @return 新会话主键
     */
    Long createMySession(Long userId, String sessionTitle);

    /**
     * 修改当前用户的会话标题。
     *
     * @param id           会话主键
     * @param sessionTitle 新会话标题
     * @return 是否修改成功
     */
    Boolean renameMySession(Long id, String sessionTitle);

    /**
     * 归档或恢复会话。
     *
     * @param id 会话ID
     * @param status 会话状态：ACTIVE/ARCHIVED
     * @return 是否更新成功
     */
    Boolean updateStatus(Long id, String status);
}

