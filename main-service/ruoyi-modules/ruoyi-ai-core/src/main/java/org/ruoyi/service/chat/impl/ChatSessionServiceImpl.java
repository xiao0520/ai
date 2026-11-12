package org.ruoyi.service.chat.impl;

import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.domain.bo.chat.ChatSessionBo;
import org.ruoyi.domain.entity.chat.ChatSession;
import org.ruoyi.mapper.chat.ChatSessionMapper;
import org.ruoyi.service.chat.IChatSessionService;
import org.springframework.stereotype.Service;
import org.ruoyi.domain.vo.chat.ChatSessionVo;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * 会话管理Service业务层处理
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ChatSessionServiceImpl implements IChatSessionService {

    private final ChatSessionMapper baseMapper;

    @Override
    public ChatSessionVo queryById(Long id) {
        return baseMapper.selectVoById(id);
    }

    @Override
    public TableDataInfo<ChatSessionVo> queryPageList(ChatSessionBo bo, PageQuery pageQuery) {
        // 筛选与排序规则位于 Mapper，业务层只负责分页调用编排。
        Page<ChatSessionVo> page = pageQuery.build();
        page.setRecords(baseMapper.selectSessionList(page, bo));
        return TableDataInfo.build(page);
    }

    @Override
    public List<ChatSessionVo> queryList(ChatSessionBo bo) {
        return baseMapper.selectSessionList(null, bo);
    }

    @Override
    public ChatSessionVo queryAccessibleById(Long id) {
        // 管理员保留会话审计能力，普通用户必须走本人会话校验。
        if (LoginHelper.isLogin() && LoginHelper.isAdmin()) {
            return baseMapper.selectVoById(id);
        }
        return queryMyById(id);
    }

    @Override
    public ChatSessionVo queryMyById(Long id) {
        // 读取详情也必须在 Service 校验归属，避免 Controller 漏调校验组件。
        requireCurrentUserOwnedSession(id);
        return baseMapper.selectVoById(id);
    }

    @Override
    public Long createMySession(Long userId, String sessionTitle) {
        // 用户标识只能取自当前登录态，禁止内部调用方伪造其他用户会话。
        requireCurrentUser(userId);
        // 新会话初始化消息数与 Token 统计为零，状态置为活跃，供后续累加。
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setSessionTitle(sessionTitle);
        session.setStatus(AiDomainConstants.STATUS_ACTIVE);
        session.setMessageCount(0);
        session.setInputTokens(0L);
        session.setOutputTokens(0L);
        session.setDeductTokens(0L);
        baseMapper.insert(session);
        return session.getId();
    }

    @Override
    public Boolean renameMySession(Long id, String sessionTitle) {
        // 普通用户的更新同时在业务层和 SQL 条件中限制会话归属。
        Long ownerUserId = requireMutationOwner(id);
        return baseMapper.updateSessionTitle(id, sessionTitle, new Date(), ownerUserId) > 0;
    }

    @Override
    public Boolean updateStatus(Long id, String status) {
        // 会话状态仅允许白名单取值，防止前端传入非法状态。
        if (!Set.of(AiDomainConstants.STATUS_ACTIVE, AiDomainConstants.STATUS_ARCHIVED).contains(status)) {
            throw new ServiceException("会话状态仅支持 ACTIVE 或 ARCHIVED");
        }
        // 管理员可执行审计归档；普通用户只能改变本人会话状态。
        Long ownerUserId = requireMutationOwner(id);
        return baseMapper.updateStatus(id, status, new Date(), ownerUserId) > 0;
    }

    /** 校验创建会话的用户确实来自当前登录态。 */
    private void requireCurrentUser(Long userId) {
        if (!LoginHelper.isLogin() || userId == null || !userId.equals(LoginHelper.getUserId())) {
            throw new ServiceException("当前用户无权创建该会话");
        }
    }

    /** 返回普通用户的归属条件；管理员返回空条件以保留管理端审计能力。 */
    private Long requireMutationOwner(Long sessionId) {
        if (!LoginHelper.isLogin()) {
            throw new ServiceException("请先登录后操作会话");
        }
        if (LoginHelper.isAdmin()) {
            return null;
        }

        // 先确认资源归属，再将同一用户条件带入更新语句，防止绕过 Controller 直接调用 Service。
        return requireCurrentUserOwnedSession(sessionId).getUserId();
    }

    /** 查询并校验当前登录用户确实拥有目标会话。 */
    private ChatSession requireCurrentUserOwnedSession(Long sessionId) {
        Long userId = LoginHelper.getUserId();
        ChatSession session = baseMapper.selectById(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new ServiceException("会话不存在或无权访问");
        }
        return session;
    }
}

