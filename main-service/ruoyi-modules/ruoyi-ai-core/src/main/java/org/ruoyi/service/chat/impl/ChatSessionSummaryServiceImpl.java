package org.ruoyi.service.chat.impl;

import org.ruoyi.constant.AiDomainConstants;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.domain.entity.chat.ChatSessionSummary;
import org.ruoyi.mapper.chat.ChatSessionSummaryMapper;
import org.ruoyi.mapper.chat.ChatSessionMapper;
import org.ruoyi.service.chat.IChatSessionSummaryService;
import org.springframework.stereotype.Service;

/**
 * 会话历史摘要查询与进度更新服务。
 *
 * @author xiaoqh
 * @date 2026-08-31
 */
@Service
@RequiredArgsConstructor
public class ChatSessionSummaryServiceImpl implements IChatSessionSummaryService {

    private final ChatSessionSummaryMapper baseMapper;
    private final ChatSessionMapper sessionMapper;

    @Override
    public ChatSessionSummary queryActiveBySessionId(Long sessionId) {
        return baseMapper.selectActiveBySessionId(sessionId, AiDomainConstants.STATUS_ACTIVE);
    }

    @Override
    public Boolean saveOrUpdate(ChatSessionSummary summary) {
        // 参数与会话存在性校验，摘要必须归属一个真实会话。
        if (summary == null || summary.getSessionId() == null || summary.getCoveredMessageSeq() == null
            || StringUtils.isBlank(summary.getSummaryContent())) {
            throw new IllegalArgumentException("会话摘要参数不能为空");
        }
        if (sessionMapper.selectById(summary.getSessionId()) == null) {
            throw new IllegalArgumentException("会话不存在");
        }

        // 摘要覆盖范围只允许前进，防止并发摘要任务回退已汇总的进度。
        ChatSessionSummary current = baseMapper.selectBySessionId(summary.getSessionId());
        if (current != null && current.getCoveredMessageSeq() != null
            && summary.getCoveredMessageSeq() < current.getCoveredMessageSeq()) {
            throw new ServiceException("会话摘要覆盖范围不能回退");
        }

        // 状态与 Token 数兜底，保证查询侧不需要处理空值。
        summary.setStatus(AiDomainConstants.STATUS_ACTIVE);
        if (summary.getSummaryTokens() == null) {
            summary.setSummaryTokens(0);
        }

        // 已有摘要按主键更新，否则插入新记录。
        if (current == null) {
            return baseMapper.insert(summary) > 0;
        }
        summary.setId(current.getId());
        return baseMapper.updateById(summary) > 0;
    }
}
