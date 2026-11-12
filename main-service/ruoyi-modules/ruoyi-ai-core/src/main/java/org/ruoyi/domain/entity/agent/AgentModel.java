package org.ruoyi.domain.entity.agent;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * Agent 可选模型白名单。实际使用仍受角色模型权限限制。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_model")
public class AgentModel extends BaseEntity {
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
     * 模型ID
     */
    private Long modelId;
    /**
     * 是否默认
     */
    private String isDefault;
    /**
     * 状态
     */
    private String status;
}
