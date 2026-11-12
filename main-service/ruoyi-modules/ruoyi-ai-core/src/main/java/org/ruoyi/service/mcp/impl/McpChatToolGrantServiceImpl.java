package org.ruoyi.service.mcp.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.domain.dto.knowledge.rag.McpToolGrant;
import org.ruoyi.domain.vo.mcp.McpServerVo;
import org.ruoyi.domain.vo.mcp.McpToolPermissionOptionVo;
import org.ruoyi.domain.vo.mcp.McpToolVo;
import org.ruoyi.domain.vo.model.ModelVo;
import org.ruoyi.service.mcp.IMcpChatToolGrantService;
import org.ruoyi.service.mcp.IMcpRoleToolPermissionService;
import org.ruoyi.service.mcp.IMcpServerService;
import org.ruoyi.service.mcp.IMcpToolService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 按当前角色、Agent 白名单与工具策略生成一次性 MCP 执行清单。 */
@Service
@RequiredArgsConstructor
public class McpChatToolGrantServiceImpl implements IMcpChatToolGrantService {

    private static final int MAX_TOOLS_PER_CHAT = 20;
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_TOOL_TIMEOUT_SECONDS = 30;
    private static final String TOOL_CAPABILITY = "TOOL";

    private final IMcpRoleToolPermissionService permissionService;
    private final IMcpToolService toolService;
    private final IMcpServerService serverService;
    private final ObjectMapper objectMapper;

    @Override
    public List<McpToolGrant> resolve(Long userId, Long agentId, ModelVo model) {
        // 模型必须声明工具能力；无 Agent 时仍按角色与普通聊天白名单授权。
        if (!supportsTools(model)) {
            return List.of();
        }
        Set<Long> authorizedIds = permissionService.queryEffectiveTools(userId, agentId).stream()
            .map(McpToolPermissionOptionVo::getToolId).collect(Collectors.toSet());
        if (authorizedIds.isEmpty()) {
            return List.of();
        }

        // 复用管理端的批量工具读取；执行阶段进一步收紧为活跃、低风险、免确认、只读。
        List<McpToolVo> tools = toolService.queryByIds(authorizedIds).stream()
            .filter(tool -> authorizedIds.contains(tool.getId()))
            .filter(this::isSafeTool)
            .filter(tool -> agentId != null
                || AiDomainConstants.FLAG_ENABLED.equals(tool.getGeneralChatEnabled()))
            .sorted(Comparator.comparing(McpToolVo::getId))
            .toList();
        if (tools.isEmpty()) {
            return List.of();
        }
        Map<Long, McpServerVo> servers = serverService.queryByIds(
                tools.stream().map(McpToolVo::getServerId).distinct().toList()).stream()
            .collect(Collectors.toMap(McpServerVo::getId, Function.identity(), (first, ignored) -> first));
        return tools.stream().filter(tool -> isSupportedServer(servers.get(tool.getServerId())))
            .limit(MAX_TOOLS_PER_CHAT)
            .map(tool -> toGrant(tool, servers.get(tool.getServerId())))
            .toList();
    }

    private boolean supportsTools(ModelVo model) {
        if (model == null || !StringUtils.hasText(model.getCapabilities())) {
            return false;
        }
        try {
            JsonNode capabilities = objectMapper.readTree(model.getCapabilities());
            if (!capabilities.isArray()) {
                return false;
            }
            for (JsonNode capability : capabilities) {
                if (TOOL_CAPABILITY.equals(capability.asText().trim().toUpperCase(Locale.ROOT))) {
                    return true;
                }
            }
        } catch (JsonProcessingException ignored) {
            // 无效的模型能力配置只能关闭工具，不得扩大授权范围。
        }
        return false;
    }

    private boolean isSafeTool(McpToolVo tool) {
        if (!AiDomainConstants.FLAG_ENABLED.equals(tool.getAdminEnabled())
            || !AiDomainConstants.MCP_TOOL_DISCOVERY_STATUS_ACTIVE.equals(tool.getDiscoveryStatus())
            || !AiDomainConstants.MCP_RISK_LEVEL_LOW.equals(tool.getRiskLevel())
            || !AiDomainConstants.FLAG_DISABLED.equals(tool.getRequireConfirmation())
            || !StringUtils.hasText(tool.getToolName())
            || !StringUtils.hasText(tool.getQualifiedName())
            || !StringUtils.hasText(tool.getInputSchema())) {
            return false;
        }
        try {
            if (!StringUtils.hasText(tool.getAnnotationsJson())
                || tool.getInputSchema().length() > 100_000
                || tool.getQualifiedName().length() > 255) {
                return false;
            }
            JsonNode annotations = objectMapper.readTree(tool.getAnnotationsJson());
            JsonNode schema = objectMapper.readTree(tool.getInputSchema());
            return annotations.path("readOnlyHint").asBoolean(false)
                && !annotations.path("destructiveHint").asBoolean(false)
                && schema.isObject() && "object".equals(schema.path("type").asText());
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            // 未明确标记只读或 Schema 无效的工具不进入自动执行链。
            return false;
        }
    }

    private boolean isSupportedServer(McpServerVo server) {
        if (server == null || !AiDomainConstants.MCP_STATUS_ENABLED.equals(server.getStatus())
            || !AiDomainConstants.MCP_TRANSPORT_STREAMABLE_HTTP.equals(server.getTransportType())
            || !StringUtils.hasText(server.getServerCode())
            || server.getServerCode().length() > 100
            || !StringUtils.hasText(server.getEndpoint())) {
            return false;
        }
        if (!AiDomainConstants.MCP_AUTH_NONE.equals(server.getAuthType())
            && !(AiDomainConstants.MCP_AUTH_BEARER.equals(server.getAuthType())
                && StringUtils.hasText(server.getCredentialKey()))) {
            return false;
        }
        try {
            URI endpoint = URI.create(server.getEndpoint());
            return ("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                && endpoint.getHost() != null && endpoint.getUserInfo() == null
                && endpoint.getQuery() == null && endpoint.getFragment() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private McpToolGrant toGrant(McpToolVo tool, McpServerVo server) {
        return new McpToolGrant(String.valueOf(server.getId()), server.getServerCode(),
            server.getEndpoint(), McpToolGrant.AuthType.fromValue(server.getAuthType()),
            AiDomainConstants.MCP_AUTH_NONE.equals(server.getAuthType()) ? null : server.getCredentialKey(),
            boundedSeconds(server.getConnectTimeout(), DEFAULT_CONNECT_TIMEOUT_SECONDS, 60),
            String.valueOf(tool.getId()), tool.getToolName(), tool.getQualifiedName(),
            tool.getInputSchema(), boundedSeconds(tool.getTimeoutSeconds(), DEFAULT_TOOL_TIMEOUT_SECONDS, 120));
    }

    private int boundedSeconds(Integer configured, int fallback, int maximum) {
        return configured == null ? fallback : Math.max(1, Math.min(maximum, configured));
    }
}
