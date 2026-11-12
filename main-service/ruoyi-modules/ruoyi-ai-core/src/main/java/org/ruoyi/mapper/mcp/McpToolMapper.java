package org.ruoyi.mapper.mcp;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.domain.bo.mcp.McpToolBo;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.mcp.McpTool;
import org.ruoyi.domain.vo.mcp.McpToolVo;

import java.util.Collection;
import java.util.List;

/**
 * MCP 工具信息 Mapper
 *
 * @author xiaoqh
 */
@Mapper
public interface McpToolMapper extends BaseMapperPlus<McpTool, McpToolVo> {

    /** 按管理端筛选条件查询 MCP 工具；传入分页对象时由分页插件处理分页。 */
    List<McpTool> selectToolList(@Param("page") Page<McpTool> page, @Param("bo") McpToolBo bo);

    /** 查询可被启用的已发现工具。 */
    List<McpTool> selectEnabledDiscoveredTools(@Param("adminEnabled") String adminEnabled,
                                               @Param("removedStatus") String removedStatus);

    /** 在指定主键范围内查询可被绑定的已发现工具。 */
    List<McpTool> selectEnabledDiscoveredByIds(@Param("toolIds") Collection<Long> toolIds,
                                               @Param("adminEnabled") String adminEnabled,
                                               @Param("removedStatus") String removedStatus);

    /** 查询一个服务下的全部工具，用于清单同步。 */
    List<McpTool> selectByServerId(@Param("serverId") Long serverId);

    /** 批量查询服务下的工具主键。 */
    List<Long> selectIdsByServerIds(@Param("serverIds") Collection<Long> serverIds);

    /** 检查服务是否已创建工具。 */
    boolean existsByServerId(@Param("serverId") Long serverId);

    /** 批量删除服务下的工具关联记录。 */
    int deleteByServerIds(@Param("serverIds") Collection<Long> serverIds);
}

