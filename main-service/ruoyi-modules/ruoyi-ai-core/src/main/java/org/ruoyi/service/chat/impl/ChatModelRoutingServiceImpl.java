package org.ruoyi.service.chat.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.domain.bo.chat.ChatSendBo;
import org.ruoyi.domain.dto.chat.ChatModelDecision;
import org.ruoyi.domain.dto.chat.PythonChatRouteCandidate;
import org.ruoyi.domain.dto.chat.PythonChatRouteFeatures;
import org.ruoyi.domain.dto.chat.PythonChatRoutePolicy;
import org.ruoyi.domain.dto.chat.PythonChatRouteRequest;
import org.ruoyi.domain.dto.chat.PythonChatRouteResponse;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.domain.vo.model.ModelVo;
import org.ruoyi.domain.vo.token.AiModelOptionVo;
import org.ruoyi.enums.ChatModelSelectionMode;
import org.ruoyi.integration.python.PythonAiExecutionClient;
import org.ruoyi.service.agent.IAgentService;
import org.ruoyi.service.chat.IChatModelRoutingService;
import org.ruoyi.service.model.IChatModelService;
import org.ruoyi.service.mcp.IMcpChatToolGrantService;
import org.ruoyi.service.token.IAiRoleModelPermissionService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 聊天模型路由服务。
 *
 * <p>固定模式直接校验用户指定的模型。自动模式由 Java 冻结授权候选和模型修订，Python LangChain
 * 仅在候选范围内选择模型，Java 在真正执行前再次校验权限、状态和修订，避免把执行授权交给 Python。</p>
 */
@Service
@RequiredArgsConstructor
public class ChatModelRoutingServiceImpl implements IChatModelRoutingService {

    /** Java 与 Python 共同遵循的路由协议版本、策略和候选摘要算法。 */
    private static final int ROUTE_POLICY_REVISION = 1;
    private static final String ROUTE_STRATEGY_RULES = "RULES";
    private static final String CANDIDATE_HASH_ALGORITHM = "SHA-256";

    /** 路由候选必须是聊天模型；未声明能力时按通用能力参与匹配。 */
    private static final String MODEL_CATEGORY_CHAT = "chat";
    private static final String DEFAULT_CAPABILITY = "GENERAL";
    private static final String TOOL_CAPABILITY = "TOOL";

    /** Java 未调用 Python 路由器时记录的模型决策原因。 */
    private static final String REASON_FIXED = "FIXED";
    private static final String REASON_SINGLE_CANDIDATE = "SINGLE_CANDIDATE";

    /** 路由输入估算和模型分层缺省值，用于候选过滤及确定性排序。 */
    private static final long ROUTING_INPUT_OVERHEAD_TOKENS = 16L;
    private static final int DEFAULT_QUALITY_TIER = 3;
    private static final int DEFAULT_LATENCY_TIER = 3;

    /** 路由优先级上限及未知价格哨兵值，防止异常配置改变兜底顺序。 */
    private static final int MAX_ROUTE_PRIORITY = 10_000;
    private static final BigDecimal UNKNOWN_COMBINED_COST = BigDecimal.valueOf(999_999_999L);

    private final IChatModelService modelService;
    private final IAiRoleModelPermissionService roleModelPermissionService;
    private final IAgentService agentService;
    private final IMcpChatToolGrantService mcpChatToolGrantService;
    private final PythonAiExecutionClient pythonAiExecutionClient;
    private final ObjectMapper objectMapper;

    @Override
    public ChatModelDecision resolve(ChatSendBo command, Long userId) {
        if (command.getModelMode() == null) {
            throw new ServiceException("模型选择模式不能为空");
        }
        return switch (command.getModelMode()) {
            case FIXED -> resolveFixed(command, userId);
            case AUTO -> resolveAuto(command, userId);
        };
    }

    /** 校验用户明确选择的模型，不调用 Python 路由器。 */
    private ChatModelDecision resolveFixed(ChatSendBo command, Long userId) {
        if (command.getModelId() == null) {
            throw new ServiceException("指定模型模式必须提供模型ID");
        }
        ModelVo model = requireAuthorizedChatModel(userId, command.getAgentId(), command.getModelId());
        return new ChatModelDecision(ChatModelSelectionMode.FIXED, model, REASON_FIXED, null, null);
    }

    /**
     * 在 Java 核定的候选快照内完成自动路由。
     *
     * <p>远程路由前后各校验一次：前一次限制 Python 可见候选，后一次处理调用期间权限或模型配置变化。</p>
     */
    private ChatModelDecision resolveAuto(ChatSendBo command, Long userId) {
        // Java 先冻结当前授权范围和可执行模型配置，Python 不参与权限计算。
        List<AiModelOptionVo> authorizedOptions = requireAuthorizedModelOptions(command, userId);
        long estimatedInputTokens = estimateInputTokens(command.getContent()) + ROUTING_INPUT_OVERHEAD_TOKENS;
        List<ModelVo> authorizedCandidates = queryAutoCandidates(authorizedOptions, estimatedInputTokens);
        // 以实际可执行的授权工具决定是否只在 TOOL 模型中路由，避免普通聊天落到无工具模型。
        boolean toolRequired = hasExecutableTools(command, userId, authorizedCandidates);
        List<ModelVo> candidates = toolRequired
            ? authorizedCandidates.stream().filter(this::supportsTools).toList()
            : authorizedCandidates;
        String candidateSnapshotHash = generateCandidateSnapshotHash(candidates);

        // 只有一个候选时直接返回，避免一次没有选择价值的远程调用。
        if (candidates.size() == 1) {
            return singleCandidateDecision(candidates.get(0), candidateSnapshotHash);
        }

        // Python 只负责候选内选择，候选摘要用于防止请求与响应串单或快照错配。
        AgentVo agent = queryAgent(command.getAgentId());
        Long fallbackModelId = determineFallbackModelId(agent, candidates);
        PythonChatRouteRequest request = buildRouteRequest(
            command, agent, candidates, estimatedInputTokens, candidateSnapshotHash, fallbackModelId, toolRequired);
        PythonChatRouteResponse response = pythonAiExecutionClient.routeChat(request);
        requireMatchingResponse(request, response);
        requireSelectedCandidate(candidates, response);

        // 外部调用期间配置可能变化，执行前必须按当前权限和最新发布修订再次复核。
        ModelVo selectedModel = requireCurrentAutoModel(command, userId, response);
        return new ChatModelDecision(ChatModelSelectionMode.AUTO, selectedModel, response.reasonCode(),
            response.policyRevision(), candidateSnapshotHash);
    }

    /** 查询角色权限，存在 Agent 时由 Agent 服务返回角色权限与模型白名单的交集。 */
    private List<AiModelOptionVo> requireAuthorizedModelOptions(ChatSendBo command, Long userId) {
        List<AiModelOptionVo> authorizedOptions = command.getAgentId() == null
            ? roleModelPermissionService.queryEffectiveModels(userId)
            : agentService.queryAvailableModels(command.getAgentId());
        if (!authorizedOptions.isEmpty()) {
            return authorizedOptions;
        }
        String message = command.getAgentId() == null
            ? "当前角色没有可用于智能选择的聊天模型"
            : "当前智能体没有可用于智能选择的聊天模型";
        throw new ServiceException(message);
    }

    /** 按模型状态、用户端可见性和输入窗口过滤候选，并固定排序以生成稳定快照。 */
    private List<ModelVo> queryAutoCandidates(List<AiModelOptionVo> authorizedOptions,
                                              long estimatedInputTokens) {
        Set<Long> authorizedModelIds = authorizedOptions.stream()
            .map(AiModelOptionVo::getModelId)
            .collect(Collectors.toSet());
        List<ModelVo> candidates = modelService.queryByIds(authorizedModelIds).stream()
            .filter(this::isAutoCandidate)
            .filter(model -> model.getMaxInputTokens() >= estimatedInputTokens)
            .sorted(Comparator.comparing(ModelVo::getId))
            .toList();
        if (candidates.isEmpty()) {
            throw new ServiceException("没有满足权限、配置和输入长度要求的自动路由模型");
        }
        return candidates;
    }

    /** 复用单次授权规则判断工具是否可执行，不在路由服务重复实现角色和工具安全过滤。 */
    private boolean hasExecutableTools(ChatSendBo command, Long userId, List<ModelVo> candidates) {
        return candidates.stream().filter(this::supportsTools).findFirst()
            .map(model -> !mcpChatToolGrantService.resolve(userId, command.getAgentId(), model).isEmpty())
            .orElse(false);
    }

    private boolean supportsTools(ModelVo model) {
        return parseCapabilities(model).contains(TOOL_CAPABILITY);
    }

    /** 构造不包含供应商密钥的最小路由请求。 */
    private PythonChatRouteRequest buildRouteRequest(ChatSendBo command, AgentVo agent,
                                                     List<ModelVo> candidates, long estimatedInputTokens,
                                                     String candidateSnapshotHash, Long fallbackModelId,
                                                     boolean toolRequired) {
        PythonChatRouteFeatures features = new PythonChatRouteFeatures(
            command.getKnowledgeId() != null,
            false,
            toolRequired,
            estimatedInputTokens
        );
        PythonChatRoutePolicy policy = new PythonChatRoutePolicy(
            ROUTE_POLICY_REVISION, ROUTE_STRATEGY_RULES, fallbackModelId);
        return new PythonChatRouteRequest(
            UUID.randomUUID().toString(),
            candidateSnapshotHash,
            command.getContent(),
            features,
            candidates.stream().map(this::toRouteCandidate).toList(),
            policy
        );
    }

    private ChatModelDecision singleCandidateDecision(ModelVo selectedModel, String candidateSnapshotHash) {
        return new ChatModelDecision(ChatModelSelectionMode.AUTO, selectedModel, REASON_SINGLE_CANDIDATE,
            ROUTE_POLICY_REVISION, candidateSnapshotHash);
    }

    private AgentVo queryAgent(Long agentId) {
        return agentId == null ? null : agentService.queryById(agentId);
    }

    /** 校验固定模式模型，或对自动路由结果执行最终权限复核。 */
    private ModelVo requireAuthorizedChatModel(Long userId, Long agentId, Long modelId) {
        boolean authorizedByRole = roleModelPermissionService.queryEffectiveModels(userId).stream()
            .map(AiModelOptionVo::getModelId)
            .anyMatch(modelId::equals);
        if (!authorizedByRole) {
            throw new ServiceException("当前角色无权使用所选模型");
        }

        if (agentId != null) {
            boolean authorizedByAgent = agentService.queryAvailableModels(agentId).stream()
                .map(AiModelOptionVo::getModelId)
                .anyMatch(modelId::equals);
            if (!authorizedByAgent) {
                throw new ServiceException("当前智能体无权使用所选模型");
            }
        }

        ModelVo model = modelService.queryById(modelId);
        if (!isExecutableChatModel(model)) {
            throw new ServiceException("所选聊天模型不可用");
        }
        return model;
    }

    /** 验证 Python 选择的模型仍处于当前权限范围，并保持请求时的发布修订。 */
    private ModelVo requireCurrentAutoModel(ChatSendBo command, Long userId, PythonChatRouteResponse response) {
        ModelVo currentModel = requireAuthorizedChatModel(
            userId, command.getAgentId(), response.selectedModelId());
        if (!response.selectedRevision().equals(currentModel.getPublishedRevision())
            || !isAutoCandidate(currentModel)) {
            throw new ServiceException("自动路由模型配置已变化，请重新发送");
        }
        return currentModel;
    }

    private boolean isAutoCandidate(ModelVo model) {
        return isExecutableChatModel(model) && SystemConstants.YES.equals(model.getModelShow());
    }

    /** 模型只有具备完整运行配置和有效 Token 窗口时，才允许进入实际调用链路。 */
    private boolean isExecutableChatModel(ModelVo model) {
        if (model == null || !SystemConstants.NORMAL.equals(model.getStatus())
            || !MODEL_CATEGORY_CHAT.equals(model.getCategory())) {
            return false;
        }
        return StringUtils.isNotBlank(model.getApiHost())
            && StringUtils.isNotBlank(model.getApiMode())
            && StringUtils.isNotBlank(model.getCredentialKey())
            && model.getPublishedRevision() != null
            && model.getMaxInputTokens() != null && model.getMaxInputTokens() > 0
            && model.getMaxOutputTokens() != null && model.getMaxOutputTokens() > 0;
    }

    private PythonChatRouteCandidate toRouteCandidate(ModelVo model) {
        return new PythonChatRouteCandidate(
            model.getId(),
            model.getPublishedRevision(),
            parseCapabilities(model),
            model.getMaxInputTokens(),
            model.getInputCostPerMillion(),
            model.getOutputCostPerMillion(),
            DEFAULT_QUALITY_TIER,
            DEFAULT_LATENCY_TIER,
            routePriority(model)
        );
    }

    /** 规范化管理员配置的能力标签，确保 Java 和 Python 使用相同的大小写与默认值。 */
    private List<String> parseCapabilities(ModelVo model) {
        if (StringUtils.isBlank(model.getCapabilities())) {
            return List.of(DEFAULT_CAPABILITY);
        }
        try {
            List<String> capabilities = objectMapper.readValue(
                model.getCapabilities(), new TypeReference<>() { });
            List<String> normalizedCapabilities = capabilities.stream()
                .filter(StringUtils::isNotBlank)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
            return normalizedCapabilities.isEmpty() ? List.of(DEFAULT_CAPABILITY) : normalizedCapabilities;
        } catch (JsonProcessingException error) {
            throw new ServiceException("模型能力标签配置无效：" + model.getModelName());
        }
    }

    private int routePriority(ModelVo model) {
        int configuredPriority = model.getGrayWeight() == null ? 0 : model.getGrayWeight();
        return Math.max(0, Math.min(configuredPriority, MAX_ROUTE_PRIORITY));
    }

    /** 优先使用 Agent 默认模型，否则按优先级、价格和模型 ID 生成确定性兜底。 */
    private Long determineFallbackModelId(AgentVo agent, List<ModelVo> candidates) {
        if (agent != null && agent.getModelId() != null
            && candidates.stream().anyMatch(model -> agent.getModelId().equals(model.getId()))) {
            return agent.getModelId();
        }
        return candidates.stream()
            .min(Comparator.comparingInt(this::routePriority).reversed()
                .thenComparing(this::combinedCost)
                .thenComparing(ModelVo::getId))
            .orElseThrow()
            .getId();
    }

    private BigDecimal combinedCost(ModelVo model) {
        if (model.getInputCostPerMillion() == null || model.getOutputCostPerMillion() == null) {
            return UNKNOWN_COMBINED_COST;
        }
        return model.getInputCostPerMillion().add(model.getOutputCostPerMillion());
    }

    /**
     * 为候选关键字段生成稳定摘要。
     *
     * <p>摘要不承担鉴权，只用于确认 Python 返回的结果对应本次请求的同一份候选快照。</p>
     */
    private String generateCandidateSnapshotHash(List<ModelVo> candidates) {
        String canonicalSnapshot = candidates.stream()
            .map(model -> model.getId() + ":" + model.getPublishedRevision() + ":"
                + model.getMaxInputTokens() + ":" + String.join(",", parseCapabilities(model)) + ":"
                + routePriority(model) + ":" + String.valueOf(model.getInputCostPerMillion()) + ":"
                + String.valueOf(model.getOutputCostPerMillion()))
            .collect(Collectors.joining(";"));
        try {
            byte[] digest = MessageDigest.getInstance(CANDIDATE_HASH_ALGORITHM)
                .digest(canonicalSnapshot.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("当前运行环境不支持候选模型摘要算法", error);
        }
    }

    /** 拒绝请求标识、候选摘要或策略修订不一致的远程响应。 */
    private void requireMatchingResponse(PythonChatRouteRequest request, PythonChatRouteResponse response) {
        if (response == null
            || !request.requestId().equals(response.requestId())
            || !request.candidateSnapshotHash().equals(response.candidateSnapshotHash())
            || !request.policy().revision().equals(response.policyRevision())) {
            throw new ServiceException("AI执行服务返回了不匹配的模型路由结果");
        }
    }

    /** 确认 Python 没有选择候选快照之外的模型或其他发布修订。 */
    private void requireSelectedCandidate(List<ModelVo> candidates, PythonChatRouteResponse response) {
        ModelVo selectedSnapshot = candidates.stream()
            .filter(model -> model.getId().equals(response.selectedModelId()))
            .findFirst()
            .orElseThrow(() -> new ServiceException("AI执行服务返回了候选范围外的模型"));
        if (!selectedSnapshot.getPublishedRevision().equals(response.selectedRevision())) {
            throw new ServiceException("AI执行服务返回了候选范围外的模型");
        }
    }

    /** 仅用于路由前的保守窗口筛选，最终计费以模型返回的实际 Token 用量为准。 */
    private long estimateInputTokens(String content) {
        long codePointCount = content.codePointCount(0, content.length());
        long utf8ByteCount = content.getBytes(StandardCharsets.UTF_8).length;
        long byteBasedEstimate = (utf8ByteCount + 3L) / 4L;
        return Math.max(codePointCount, byteBasedEstimate);
    }
}