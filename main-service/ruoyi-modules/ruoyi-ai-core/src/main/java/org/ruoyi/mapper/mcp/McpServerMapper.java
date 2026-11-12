package org.ruoyi.mapper.mcp;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.domain.bo.mcp.McpServerBo;
import org.ruoyi.domain.entity.mcp.McpServer;

import java.util.List;

@Mapper
public interface McpServerMapper extends BaseMapper<McpServer> {

    /** 按管理端筛选条件查询 MCP 服务；传入分页对象时由分页插件处理分页。 */
    List<McpServer> selectServerList(@Param("page") Page<McpServer> page, @Param("bo") McpServerBo bo);

    /** 按服务名称排序查询全部 MCP 服务。 */
    List<McpServer> selectAllOrderByName();

    /** 检查服务编码是否已被其他服务占用。 */
    boolean existsByServerCode(@Param("serverCode") String serverCode, @Param("excludeId") Long excludeId);
}
