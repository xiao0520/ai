package org.ruoyi.service.mcp;

import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.mcp.McpToolBo;
import org.ruoyi.domain.bo.mcp.McpToolPolicyBo;
import org.ruoyi.domain.vo.mcp.McpToolOptionVo;
import org.ruoyi.domain.vo.mcp.McpToolVo;

import java.util.Collection;
import java.util.List;

/**
 * MCP工具管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IMcpToolService {

    /**
     * 分页查询MCP工具列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return MCP工具分页列表
     */
    TableDataInfo<McpToolVo> queryPage(McpToolBo bo, PageQuery pageQuery);

    /**
     * 根据ID查询MCP工具
     *
     * @param id 主键
     * @return MCP工具
     */
    McpToolVo queryById(Long id);

    /**
     * 按ID集合查询MCP工具。
     *
     * @param ids 工具ID集合
     * @return MCP工具列表
     */
    List<McpToolVo> queryByIds(Collection<Long> ids);

    /**
     * 查询启用的MCP工具下拉选项
     *
     * @return MCP工具选项列表
     */
    List<McpToolOptionVo> queryEnabledOptions();

    /**
     * 按工具ID集合查询可执行工具选项。
     *
     * @param ids 工具ID集合
     * @return 可执行工具选项
     */
    List<McpToolOptionVo> queryEnabledOptionsByIds(Collection<Long> ids);

    /**
     * 校验工具可被智能体关联。
     *
     * @param ids 工具ID集合
     */
    void validateSelectableTools(Collection<Long> ids);

    /**
     * 更新MCP工具策略配置
     *
     * @param bo MCP工具策略
     * @return 是否更新成功
     */
    boolean updatePolicy(McpToolPolicyBo bo);

    /**
     * 修改MCP工具状态
     *
     * @param id           主键
     * @param adminEnabled 管理员启用状态
     * @return 是否修改成功
     */
    boolean updateStatus(Long id, String adminEnabled);

    /**
     * 物理清理已经从 Manifest 移除且未被引用的工具。
     *
     * @param ids 工具ID集合
     * @return 是否清理成功
     */
    boolean deleteRemoved(Collection<Long> ids);
}
