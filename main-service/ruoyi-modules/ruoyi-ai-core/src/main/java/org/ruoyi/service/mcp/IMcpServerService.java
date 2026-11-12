package org.ruoyi.service.mcp;

import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.mcp.McpServerBo;
import org.ruoyi.domain.vo.mcp.McpServerVo;

import java.util.Collection;
import java.util.List;

/**
 * MCP服务管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IMcpServerService {

    /**
     * 分页查询MCP服务列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return MCP服务分页列表
     */
    TableDataInfo<McpServerVo> queryPage(McpServerBo bo, PageQuery pageQuery);

    /**
     * 查询全部MCP服务列表
     *
     * @return MCP服务列表
     */
    List<McpServerVo> queryAll();

    /**
     * 根据ID查询MCP服务
     *
     * @param id 主键
     * @return MCP服务
     */
    McpServerVo queryById(Long id);

    /** 按 ID 集合批量读取 Server 配置，供聊天运行时构造工具快照。 */
    List<McpServerVo> queryByIds(Collection<Long> ids);

    /**
     * 新增MCP服务
     *
     * @param bo MCP服务
     * @return 是否新增成功
     */
    boolean insert(McpServerBo bo);

    /**
     * 修改MCP服务
     *
     * @param bo MCP服务
     * @return 是否修改成功
     */
    boolean update(McpServerBo bo);

    /**
     * 批量删除MCP服务
     *
     * @param ids 待删除的主键集合
     * @return 是否删除成功
     */
    boolean delete(Collection<Long> ids);

    /**
     * 修改MCP服务状态
     *
     * @param id     主键
     * @param status 状态
     * @return 是否修改成功
     */
    boolean updateStatus(Long id, String status);

    /**
     * 导入工具（从manifestJson解析并注册到指定服务）
     *
     * @param serverId     服务ID
     * @param manifestJson 工具清单JSON
     * @return 导入工具数量
     */
    int importTools(Long serverId, String manifestJson);

}
