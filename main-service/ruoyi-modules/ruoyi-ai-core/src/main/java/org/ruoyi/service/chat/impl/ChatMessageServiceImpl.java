package org.ruoyi.service.chat.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.chat.ChatMessageBo;
import org.ruoyi.domain.vo.chat.ChatCitationVo;
import org.ruoyi.domain.vo.chat.ChatMessageVo;
import org.ruoyi.mapper.chat.ChatMessageMapper;
import org.ruoyi.service.chat.IChatCitationService;
import org.ruoyi.service.chat.IChatMessageService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 聊天消息查询服务。
 *
 * 消息主体由消息 Mapper 分页查询，RAG 引用随后按消息 ID 批量装配，避免列表查询出现逐条访问数据库。
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl implements IChatMessageService {

    private final ChatMessageMapper baseMapper;
    private final IChatCitationService citationService;

    @Override
    public ChatMessageVo queryById(Long id) {
        ChatMessageVo message = baseMapper.selectVoById(id);
        if (message == null) {
            return null;
        }
        Map<Long, List<ChatCitationVo>> citationsByMessage =
            citationService.queryByMessageIds(List.of(id));
        message.setCitations(citationsByMessage.getOrDefault(id, List.of()));
        return message;
    }

    @Override
    public TableDataInfo<ChatMessageVo> queryPageList(ChatMessageBo bo, PageQuery pageQuery) {
        // 消息审计筛选与排序规则由 Mapper XML 统一维护。
        Page<ChatMessageVo> page = pageQuery.build();
        page.setRecords(baseMapper.selectMessageList(page, bo));
        attachCitations(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    public List<ChatMessageVo> queryList(ChatMessageBo bo) {
        List<ChatMessageVo> messages = baseMapper.selectMessageList(null, bo);
        attachCitations(messages);
        return messages;
    }

    /** 一次批量查询全部引用，避免消息列表出现 N+1 查询。 */
    private void attachCitations(List<ChatMessageVo> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<Long> messageIds = messages.stream().map(ChatMessageVo::getId).toList();
        Map<Long, List<ChatCitationVo>> citationsByMessage =
            citationService.queryByMessageIds(messageIds);
        messages.forEach(message -> message.setCitations(
            citationsByMessage.getOrDefault(message.getId(), List.of())));
    }
}
