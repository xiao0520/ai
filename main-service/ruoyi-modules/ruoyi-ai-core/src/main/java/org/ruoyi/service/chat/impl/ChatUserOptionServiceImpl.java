package org.ruoyi.service.chat.impl;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.enums.ModelType;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.domain.vo.agent.ChatMyAgentOptionVo;
import org.ruoyi.domain.vo.knowledge.ChatKnowledgeOptionVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.domain.vo.model.MyModelOptionVo;
import org.ruoyi.domain.vo.token.AiModelOptionVo;
import org.ruoyi.service.agent.IAgentService;
import org.ruoyi.service.chat.IChatUserOptionService;
import org.ruoyi.service.knowledge.IKnowledgeAclService;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.IKnowledgeRetrievalScopeService;
import org.ruoyi.service.model.IChatModelService;
import org.ruoyi.service.token.IAiRoleModelPermissionService;
import org.ruoyi.support.RagChatProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户聊天页选项Service业务层处理。
 */
@Service
@RequiredArgsConstructor
public class ChatUserOptionServiceImpl implements IChatUserOptionService {

    private final IAiRoleModelPermissionService roleModelPermissionService;
    private final IAgentService agentService;
    private final IChatModelService chatModelService;
    private final IKnowledgeAclService knowledgeAclService;
    private final IKnowledgeInfoService knowledgeInfoService;
    private final IKnowledgeRetrievalScopeService retrievalScopeService;
    private final RagChatProperties ragChatProperties;

    @Override
    public List<MyModelOptionVo> queryMyModels() {
        // 角色模型权限先决定候选范围，仅保留聊天分类模型。
        List<AiModelOptionVo> authorizedModels = roleModelPermissionService
            .queryEffectiveModels(LoginHelper.getUserId())
            .stream().filter(model -> ModelType.CHAT.getKey().equals(model.getCategory())).toList();
        if (authorizedModels.isEmpty()) {
            return List.of();
        }

        // 再按启用状态和展示开关过滤，避免向用户侧返回内部运行配置。
        Set<Long> modelIds = authorizedModels.stream().map(AiModelOptionVo::getModelId).collect(Collectors.toSet());
        Map<Long, MyModelOptionVo> modelMap = chatModelService.queryVisibleChatOptions(modelIds).stream()
            .collect(Collectors.toMap(MyModelOptionVo::getId, option -> option, (left, right) -> left,
                LinkedHashMap::new));

        // 按授权顺序组装结果，已被隐藏或停用的模型直接丢弃。
        return authorizedModels.stream().map(option -> modelMap.get(option.getModelId()))
            .filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<ChatMyAgentOptionVo> queryMyAgents() {
        // Agent Service 已完成 ACL 和模型权限交集校验，此处仅转换为用户侧最小展示对象。
        return agentService.queryEnabledOptions().stream().map(this::toAgentOption).toList();
    }

    @Override
    public List<ChatKnowledgeOptionVo> queryMyKnowledgeOptions() {
        List<Long> accessibleIds = knowledgeAclService.accessibleKnowledgeIds();
        if (accessibleIds.isEmpty()) {
            return List.of();
        }
        // 页面只展示启用且存在可检索发布索引的库，避免用户选择后才发现不可用。
        return knowledgeInfoService.queryByIds(accessibleIds).stream()
            .filter(item -> SystemConstants.NORMAL.equals(item.getStatus()))
            .filter(item -> StringUtils.isNotBlank(item.getCurrentIndexVersion()))
            .filter(item -> retrievalScopeService.isReady(item.getId(), ragChatProperties.toRetrievalProfile()))
            .map(this::toKnowledgeOption)
            .toList();
    }

    private ChatKnowledgeOptionVo toKnowledgeOption(KnowledgeInfoVo knowledge) {
        return new ChatKnowledgeOptionVo(knowledge.getId(), knowledge.getName());
    }

    /**
     * 将完整智能体对象转换为用户侧展示对象。
     *
     * 系统提示词和内部资源绑定仅允许后续受信任的运行端读取。
     */
    private ChatMyAgentOptionVo toAgentOption(AgentVo agent) {
        ChatMyAgentOptionVo option = new ChatMyAgentOptionVo();
        option.setId(agent.getId());
        option.setAgentCode(agent.getAgentCode());
        option.setAgentName(agent.getAgentName());
        option.setAgentDescribe(agent.getAgentDescribe());
        option.setAgentShow(agent.getAgentShow());
        option.setModelId(agent.getModelId());
        option.setModelName(agent.getModelName());
        option.setEnableThinking(agent.getEnableThinking());
        option.setWelcomeMessage(agent.getWelcomeMessage());
        option.setSuggestedQuestions(agent.getSuggestedQuestions());
        return option;
    }
}
