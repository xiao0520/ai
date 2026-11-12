package org.ruoyi.domain.vo.agent;

import lombok.Data;

/** Agent 与 Skill 的绑定视图。 */
@Data
public class AgentSkillBindingVo {
    private Long agentId;
    private String agentCode;
    private String agentName;
    private String status;
    private String configJson;
}
