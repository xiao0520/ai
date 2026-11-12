package org.ruoyi.mapper.agent;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.domain.bo.agent.AgentBo;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.agent.Agent;
import org.ruoyi.domain.vo.agent.AgentVo;

import java.util.Collection;
import java.util.List;
/**
 * 智能体信息 Mapper
 *
 * @author xiaoqh
 */
@Mapper
public interface AgentMapper extends BaseMapperPlus<Agent, AgentVo> {

    /** 按管理端筛选条件查询智能体；传入分页对象时由分页插件处理分页。 */
    List<Agent> selectAgentList(@Param("page") Page<Agent> page, @Param("bo") AgentBo bo);

    /** 统计默认模型被智能体引用的数量。 */
    long countByModelIds(@Param("modelIds") Collection<Long> modelIds);

    List<Agent> selectEnabledList(@Param("status") String status);

    boolean existsByAgentCode(@Param("agentCode") String agentCode, @Param("excludeId") Long excludeId);
}

