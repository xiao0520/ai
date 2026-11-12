package org.ruoyi.domain.vo.skill;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 当前 Skill 绑定的 Agent 信息。
 */
@Data
public class SkillBoundAgentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 智能体ID
     */
    private Long agentId;
    /**
     * 智能体编码
     */
    private String agentCode;
    /**
     * 智能体名称
     */
    private String agentName;
    /**
     * 状态
     */
    private String status;
    /**
     * 配置JSON
     */
    private String configJson;
}
