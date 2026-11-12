package org.ruoyi.service.mcp.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.constant.AiDictTypes;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.constant.AiSystemConfigKeys;
import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.mcp.McpServerBo;
import org.ruoyi.domain.entity.mcp.McpServer;
import org.ruoyi.domain.entity.mcp.McpTool;
import org.ruoyi.domain.vo.mcp.McpServerVo;
import org.ruoyi.mapper.mcp.McpRoleToolPermissionMapper;
import org.ruoyi.mapper.mcp.McpServerMapper;
import org.ruoyi.mapper.mcp.McpToolMapper;
import org.ruoyi.service.mcp.IMcpServerService;
import org.ruoyi.service.agent.IAgentMcpToolReferenceService;
import org.ruoyi.support.AiBusinessDict;
import org.ruoyi.support.AiRuntimeConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MCP服务管理Service实现类
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Service
@RequiredArgsConstructor
public class McpServerServiceImpl implements IMcpServerService {

    /** 单次导入工具数量上限 */
    private static final int MAX_TOOLS_PER_IMPORT = 500;
    /** Schema最大长度限制（256KB） */
    private static final int MAX_SCHEMA_LENGTH = 262_144;

    private final McpServerMapper baseMapper;
    private final McpToolMapper toolMapper;
    private final McpRoleToolPermissionMapper roleToolMapper;
    private final IAgentMcpToolReferenceService agentToolReferenceService;
    private final ObjectMapper objectMapper;
    private final AiRuntimeConfig runtimeConfig;
    private final AiBusinessDict businessDict;

    @Override
    public TableDataInfo<McpServerVo> queryPage(McpServerBo bo, PageQuery pageQuery) {
        // 筛选与排序规则由 Mapper XML 管理，避免服务层直接构造持久化条件。
        Page<McpServer> page = pageQuery.build();
        page.setRecords(baseMapper.selectServerList(page, bo));

        // 查询结果仅在业务层转换为前端展示对象，不混入数据库筛选逻辑。
        List<McpServerVo> rows = page.getRecords().stream()
            .map(entity -> MapstructUtils.convert(entity, McpServerVo.class)).toList();
        return new TableDataInfo<>(rows, page.getTotal());
    }

    @Override
    public List<McpServerVo> queryAll() {
        return baseMapper.selectAllOrderByName()
            .stream().map(entity -> MapstructUtils.convert(entity, McpServerVo.class)).toList();
    }

    @Override
    public McpServerVo queryById(Long id) {
        McpServer entity = baseMapper.selectById(id);
        return entity == null ? null : MapstructUtils.convert(entity, McpServerVo.class);
    }

    @Override
    public List<McpServerVo> queryByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return baseMapper.selectByIds(ids).stream()
            .map(entity -> MapstructUtils.convert(entity, McpServerVo.class)).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insert(McpServerBo bo) {
        // 新服务初始为未部署状态，连接超时未配置时兜底为 30 秒。
        validate(bo, null);
        McpServer entity = MapstructUtils.convert(bo, McpServer.class);
        entity.setRuntimeStatus(AiDomainConstants.MCP_RUNTIME_STATUS_NOT_DEPLOYED);
        entity.setStatus(bo.getStatus());
        entity.setConnectTimeout(entity.getConnectTimeout() == null ? 30 : entity.getConnectTimeout());
        return baseMapper.insert(entity) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(McpServerBo bo) {
        // 运行时状态与发现时间由系统侧维护，页面修改不允许覆盖。
        McpServer current = requireServer(bo.getId());
        validate(bo, bo.getId());
        McpServer entity = MapstructUtils.convert(bo, McpServer.class);
        entity.setRuntimeStatus(current.getRuntimeStatus());
        entity.setRuntimeMessage(current.getRuntimeMessage());
        entity.setLastDiscoveredAt(current.getLastDiscoveredAt());
        return baseMapper.updateById(entity) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Collection<Long> ids) {
        // 规范化待删除主键并一次确认所有服务都存在，避免循环查询产生 N+1 开销。
        List<Long> serverIds = ids == null ? List.of()
            : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (serverIds.isEmpty()) {
            throw new ServiceException("请选择需要删除的 MCP Server");
        }
        if (baseMapper.selectByIds(serverIds).size() != serverIds.size()) {
            throw new ServiceException("所选 MCP Server 包含不存在或已删除的数据");
        }

        // 汇总全部关联工具，角色授权和 Agent 绑定任一存在时均禁止删除。
        List<Long> toolIds = toolMapper.selectIdsByServerIds(serverIds);
        long roleBindings = toolIds.isEmpty() ? 0L : roleToolMapper.countByToolIds(toolIds);
        long agentBindings = toolIds.isEmpty() ? 0L : agentToolReferenceService.countBindings(toolIds);
        if (roleBindings > 0 || agentBindings > 0) {
            throw new ServiceException("Server 下的工具仍被角色或 Agent 使用，不能删除");
        }

        // 在同一事务中先移除工具记录，再删除服务主记录，避免保留孤儿工具。
        if (!toolIds.isEmpty()) {
            toolMapper.deleteByServerIds(serverIds);
        }
        return baseMapper.deleteByIds(serverIds) > 0;
    }

    @Override
    public boolean updateStatus(Long id, String status) {
        // 状态按字典标准化后更新，非法值直接拒绝。
        requireServer(id);
        McpServer update = new McpServer();
        update.setId(id);
        update.setStatus(businessDict.requireSpecifiedValue(AiDictTypes.MCP_SERVER_STATUS, status));
        return baseMapper.updateById(update) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int importTools(Long serverId, String manifestJson) {
        // 解析并限制导入清单，空清单不能触发工具移除逻辑。
        McpServer server = requireServer(serverId);
        JsonNode toolsNode = parseToolsNode(manifestJson);
        validateImportSize(toolsNode);

        // 以工具名称索引既有数据，导入时按名称执行新增或更新。
        Map<String, McpTool> existing = queryExistingTools(serverId);
        Set<String> importedNames = new HashSet<>();
        Date now = new Date();

        // 写入本次清单中的工具，并记录已出现名称供后续识别移除项。
        for (JsonNode node : toolsNode) {
            importTool(server, node, existing, importedNames, now);
        }

        // 本次未出现的历史工具标记为已移除并停用，保留记录以支持审计与安全清理。
        markMissingToolsRemoved(existing, importedNames, now);

        // 最后更新服务发现时间，让运行端能够判断工具目录的新鲜度。
        updateLastDiscoveredAt(serverId, now);
        return importedNames.size();
    }

    /** 校验导入清单非空且不超过运营配置的单次上限。 */
    private void validateImportSize(JsonNode toolsNode) {
        if (toolsNode.isEmpty()) {
            throw new ServiceException("Manifest工具列表不能为空，避免误将现有工具全部标记为已移除");
        }
        int maxToolsPerImport = maxToolsPerImport();
        if (toolsNode.size() > maxToolsPerImport) {
            throw new ServiceException("单次最多导入" + maxToolsPerImport + "个工具");
        }
    }

    /** 查询指定服务已有工具，并按工具名称建立索引。 */
    private Map<String, McpTool> queryExistingTools(Long serverId) {
        return toolMapper.selectByServerId(serverId)
            .stream().collect(Collectors.toMap(McpTool::getToolName, Function.identity()));
    }

    /** 导入单个工具，并按名称决定新增或更新。 */
    private void importTool(McpServer server, JsonNode node, Map<String, McpTool> existing,
                            Set<String> importedNames, Date discoveredAt) {
        String toolName = requireToolName(node, importedNames);
        JsonNode input = node.get("inputSchema");
        if (input == null || !input.isObject()) {
            throw new ServiceException(toolName + " 的inputSchema必须是JSON对象");
        }

        // 提取 Schema 和元数据后计算摘要，用于识别工具定义是否变化。
        String inputSchema = input.toString();
        String outputSchema = json(node.get("outputSchema"));
        String annotations = json(node.get("annotations"));
        String meta = json(node.get("_meta"));
        validateJsonLength(toolName, inputSchema, outputSchema, annotations, meta);
        String digest = calculateSchemaDigest(inputSchema, outputSchema, annotations, meta);

        // 新工具默认停用；既有工具保留管理员已配置的风险、启用和超时策略。
        McpTool tool = existing.get(toolName);
        boolean isNewTool = tool == null;
        if (isNewTool) {
            tool = createImportedTool(server, toolName);
        }
        applyImportedDefinition(tool, node, inputSchema, outputSchema, annotations, meta, digest,
            discoveredAt, isNewTool);
        applyConfirmationPolicy(tool, node);
        if (isNewTool) {
            toolMapper.insert(tool);
        } else {
            toolMapper.updateById(tool);
        }
    }

    /** 校验工具名称并拒绝同一清单中的重复项。 */
    private String requireToolName(JsonNode node, Set<String> importedNames) {
        String toolName = text(node, "name");
        if (!StringUtils.hasText(toolName) || toolName.length() > 200) {
            throw new ServiceException("工具name不能为空且不能超过200个字符");
        }
        if (!importedNames.add(toolName)) {
            throw new ServiceException("Manifest存在重复工具：" + toolName);
        }
        return toolName;
    }

    /** 创建首次发现的工具默认配置。 */
    private McpTool createImportedTool(McpServer server, String toolName) {
        McpTool tool = new McpTool();
        tool.setServerId(server.getId());
        tool.setToolName(toolName);
        tool.setQualifiedName(server.getServerCode() + ':' + toolName);
        tool.setAdminEnabled(AiDomainConstants.FLAG_DISABLED);
        tool.setGeneralChatEnabled(AiDomainConstants.FLAG_DISABLED);
        tool.setRiskLevel(server.getDefaultRiskLevel());
        tool.setRequireConfirmation(AiDomainConstants.FLAG_DISABLED);
        tool.setTimeoutSeconds(30);
        tool.setSourceType(businessDict.requireSpecifiedValue(AiDictTypes.MCP_TOOL_SOURCE_TYPE,
            AiDomainConstants.MCP_TOOL_SOURCE_IMPORTED));
        tool.setVisibleResultFields(AiDomainConstants.EMPTY_JSON_ARRAY);
        return tool;
    }

    /** 用最新清单覆盖工具定义，并按摘要更新发现状态。 */
    private void applyImportedDefinition(McpTool tool, JsonNode node, String inputSchema, String outputSchema,
                                         String annotations, String meta, String digest, Date discoveredAt,
                                         boolean isNewTool) {
        String oldDigest = tool.getSchemaDigest();
        tool.setDisplayName(resolveTitle(node, tool.getToolName()));
        tool.setDescription(text(node, "description"));
        tool.setInputSchema(inputSchema);
        tool.setOutputSchema(outputSchema);
        tool.setAnnotationsJson(annotations);
        tool.setMetaJson(meta);
        tool.setSchemaDigest(digest);
        tool.setDiscoveryStatus(!isNewTool && !digest.equals(oldDigest)
            ? AiDomainConstants.MCP_TOOL_DISCOVERY_STATUS_CHANGED
            : AiDomainConstants.MCP_TOOL_DISCOVERY_STATUS_ACTIVE);
        tool.setLastDiscoveredAt(discoveredAt);
    }

    /** 对破坏性工具和高风险等级统一施加二次确认策略。 */
    private void applyConfirmationPolicy(McpTool tool, JsonNode node) {
        if (isDestructive(node)) {
            tool.setRequireConfirmation(AiDomainConstants.FLAG_ENABLED);
            if (AiDomainConstants.MCP_RISK_LEVEL_LOW.equals(tool.getRiskLevel())) {
                tool.setRiskLevel(AiDomainConstants.MCP_RISK_LEVEL_HIGH);
            }
        }
        if (confirmationRiskLevels().contains(tool.getRiskLevel())) {
            tool.setRequireConfirmation(AiDomainConstants.FLAG_ENABLED);
        }
    }

    /** 将本次清单未包含的历史工具标记为已移除。 */
    private void markMissingToolsRemoved(Map<String, McpTool> existing, Set<String> importedNames, Date discoveredAt) {
        existing.values().stream().filter(tool -> !importedNames.contains(tool.getToolName())).forEach(tool -> {
            tool.setDiscoveryStatus(AiDomainConstants.MCP_TOOL_DISCOVERY_STATUS_REMOVED);
            tool.setAdminEnabled(AiDomainConstants.FLAG_DISABLED);
            tool.setLastDiscoveredAt(discoveredAt);
            toolMapper.updateById(tool);
        });
    }

    /** 更新 MCP 服务的最后发现时间。 */
    private void updateLastDiscoveredAt(Long serverId, Date discoveredAt) {
        McpServer update = new McpServer();
        update.setId(serverId);
        update.setLastDiscoveredAt(discoveredAt);
        baseMapper.updateById(update);
    }

    /** 计算工具定义的稳定摘要。 */
    private String calculateSchemaDigest(String inputSchema, String outputSchema, String annotations, String meta) {
        String digestSource = inputSchema + '|' + StrUtil.nullToEmpty(outputSchema) + '|'
            + StrUtil.nullToEmpty(annotations) + '|' + StrUtil.nullToEmpty(meta);
        return DigestUtil.sha256Hex(digestSource.getBytes(StandardCharsets.UTF_8));
    }

    /** 解析导入清单中的工具数组。 */
    private JsonNode parseToolsNode(String manifestJson) {
        try {
            JsonNode root = objectMapper.readTree(manifestJson);
            JsonNode tools = root.isArray() ? root : root.path("result").has("tools")
                ? root.path("result").path("tools") : root.path("tools");
            if (!tools.isArray()) {
                throw new ServiceException("Manifest必须包含tools数组");
            }
            return tools;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("Manifest不是有效JSON：" + e.getMessage());
        }
    }

    /** 读取工具展示名称，不存在时使用工具名称。 */
    private String resolveTitle(JsonNode node, String fallback) {
        String title = text(node, "title");
        if (!StringUtils.hasText(title) && node.path("annotations").isObject()) {
            title = text(node.path("annotations"), "title");
        }
        return StrUtil.blankToDefault(title, fallback);
    }

    /** 判断工具是否标记为破坏性操作。 */
    private boolean isDestructive(JsonNode node) {
        return node.path("annotations").path("destructiveHint").asBoolean(false);
    }

    /** 读取 JSON 节点中的文本字段。 */
    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 将 JSON 节点序列化为字符串。 */
    private String json(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.toString();
    }

    /** 校验工具 Schema 与元数据的长度。 */
    private void validateJsonLength(String toolName, String... values) {
        for (String value : values) {
            if (value != null && value.length() > maxSchemaLength()) {
                throw new ServiceException(toolName + " 的Schema或元数据过大");
            }
        }
    }

    /** 校验 MCP 服务参数及其唯一性约束。 */
    private void validate(McpServerBo bo, Long excludeId) {
        normalizeBusinessOptions(bo);
        if (AiDomainConstants.MCP_TRANSPORT_STREAMABLE_HTTP.equals(bo.getTransportType())) {
            if (!StringUtils.hasText(bo.getEndpoint())) {
                throw new ServiceException("Streamable HTTP必须填写服务地址");
            }
            if (!bo.getEndpoint().startsWith("https://") && !bo.getEndpoint().startsWith("http://")) {
                throw new ServiceException("服务地址必须是HTTP或HTTPS地址");
            }
            bo.setRuntimeKey(null);
        } else if (AiDomainConstants.MCP_TRANSPORT_STDIO.equals(bo.getTransportType())) {
            if (!StringUtils.hasText(bo.getRuntimeKey())) {
                throw new ServiceException("STDIO必须填写部署侧运行时键");
            }
            bo.setEndpoint(null);
        } else {
            throw new ServiceException("当前传输方式暂不支持");
        }
        if (!AiDomainConstants.MCP_AUTH_NONE.equals(bo.getAuthType()) && !StringUtils.hasText(bo.getCredentialKey())) {
            throw new ServiceException("启用认证时必须填写凭证别名");
        }
        if (baseMapper.existsByServerCode(bo.getServerCode(), excludeId)) {
            throw new ServiceException("Server编码已存在");
        }
        if (excludeId != null) {
            McpServer current = requireServer(excludeId);
            if (!current.getServerCode().equals(bo.getServerCode())
                && toolMapper.existsByServerId(excludeId)) {
                throw new ServiceException("Server已有工具后不能修改Server编码");
            }
        }
    }

    /** 将请求中的可维护选项校验并标准化为系统字典值。 */
    private void normalizeBusinessOptions(McpServerBo bo) {
        bo.setServerType(businessDict.requireSpecifiedValue(AiDictTypes.MCP_SERVER_TYPE, bo.getServerType()));
        bo.setTransportType(businessDict.requireSpecifiedValue(AiDictTypes.MCP_TRANSPORT_TYPE, bo.getTransportType()));
        bo.setAuthType(businessDict.requireSpecifiedValue(AiDictTypes.MCP_AUTH_TYPE, bo.getAuthType()));
        bo.setDefaultRiskLevel(businessDict.requireSpecifiedValue(
            AiDictTypes.MCP_RISK_LEVEL, bo.getDefaultRiskLevel()));
        bo.setStatus(StringUtils.hasText(bo.getStatus())
            ? businessDict.requireSpecifiedValue(AiDictTypes.MCP_SERVER_STATUS, bo.getStatus())
            : businessDict.defaultValue(AiDictTypes.MCP_SERVER_STATUS));
    }

    /** 查询必须存在的 MCP 服务。 */
    private McpServer requireServer(Long id) {
        if (id == null) {
            throw new ServiceException("Server ID不能为空");
        }
        McpServer server = baseMapper.selectById(id);
        if (server == null) {
            throw new ServiceException("MCP Server不存在或已删除");
        }
        return server;
    }

    /** 获取单次可导入的工具数量上限。 */
    private int maxToolsPerImport() {
        return runtimeConfig.getPositiveInt(AiSystemConfigKeys.MCP_IMPORT_MAX_TOOLS,
            MAX_TOOLS_PER_IMPORT, 10_000);
    }

    /** 获取单个工具 Schema 或元数据的长度上限。 */
    private int maxSchemaLength() {
        return runtimeConfig.getPositiveInt(AiSystemConfigKeys.MCP_IMPORT_MAX_SCHEMA_LENGTH,
            MAX_SCHEMA_LENGTH, 2_000_000);
    }

    /** 获取需要二次确认的 MCP 工具风险等级。 */
    private Set<String> confirmationRiskLevels() {
        return runtimeConfig.getAllowedValuesIgnoreCase(AiSystemConfigKeys.MCP_CONFIRMATION_RISK_LEVELS,
            businessDict.values(AiDictTypes.MCP_RISK_LEVEL));
    }
}
