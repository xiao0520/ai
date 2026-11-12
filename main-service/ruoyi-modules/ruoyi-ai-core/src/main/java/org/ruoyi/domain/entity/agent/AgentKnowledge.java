package org.ruoyi.domain.entity.agent;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * Agent 知识库能力范围关联。不是知识库授权记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_knowledge")
public class AgentKnowledge extends BaseEntity {
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
     * 知识库ID
     */
    private Long knowledgeId;
    /**
     * 状态
     */
    private String status;
}
