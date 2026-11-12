package org.ruoyi.domain.entity.agent;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * Agent 与 Skill 的关联。第一阶段仅保存，不执行 Skill。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_skill")
public class AgentSkill extends BaseEntity {
    /**
     * 主键ID
     */
    @TableId("id")
    private Long id;
    /**
     * 智能体ID
     */
    private Long agentId;
    /**
     * 技能名称
     */
    private String skillName;
    /**
     * 配置JSON
     */
    private String configJson;
    /**
     * 状态
     */
    private String status;
}
