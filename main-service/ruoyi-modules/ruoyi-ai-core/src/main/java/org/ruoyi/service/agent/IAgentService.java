package org.ruoyi.service.agent;

import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.agent.AgentBo;
import org.ruoyi.domain.bo.agent.AgentAclSaveBo;
import org.ruoyi.domain.vo.agent.AgentAclVo;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.domain.vo.token.AiModelOptionVo;

import java.util.Collection;
import java.util.List;

/**
 * 智能体管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IAgentService {

    /**
     * 分页查询智能体列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 智能体分页列表
     */
    TableDataInfo<AgentVo> queryPageList(AgentBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的智能体列表（用于导出）
     *
     * @param bo 查询条件
     * @return 智能体列表
     */
    List<AgentVo> queryList(AgentBo bo);

    /**
     * 根据ID查询智能体（展开 JSON 数组字段为 List，关联填充模型/工具/知识库名称）
     *
     * @param id 主键
     * @return 智能体
     */
    AgentVo queryById(Long id);

    /**
     * 新增智能体
     *
     * @param bo 智能体
     * @return 是否新增成功
     */
    Boolean insertByBo(AgentBo bo);

    /**
     * 修改智能体
     *
     * @param bo 智能体
     * @return 是否修改成功
     */
    Boolean updateByBo(AgentBo bo);

    /**
     * 批量删除智能体
     *
     * @param ids 待删除的主键集合
     * @return 是否删除成功
     */
    Boolean deleteByIds(Collection<Long> ids);

    /**
     * 查询启用的智能体下拉选项（用户端聊天页选择用，status=0）
     *
     * @return 智能体选项列表
     */
    List<AgentVo> queryEnabledOptions();

    /**
     * 查询当前登录用户在指定 Agent 下可选择的模型。
     *
     * @param id Agent ID
     * @return Agent模型白名单与角色模型权限的交集
     */
    List<AiModelOptionVo> queryAvailableModels(Long id);

    /**
     * 查询 Agent 的角色、部门使用权限
     *
     * @param id Agent ID
     * @return 权限列表
     */
    List<AgentAclVo> listAcl(Long id);

    /**
     * 保存 Agent 的角色、部门使用权限
     *
     * @param id 主键
     * @param command 权限配置
     * @return 是否成功
     */
    Boolean saveAcl(Long id, AgentAclSaveBo command);
}
