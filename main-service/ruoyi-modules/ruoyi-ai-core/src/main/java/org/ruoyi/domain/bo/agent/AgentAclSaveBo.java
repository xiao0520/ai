package org.ruoyi.domain.bo.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 保存一个 Agent 的角色、部门使用范围。
 */
@Data
public class AgentAclSaveBo {
    /**
     * 授权记录列表
     */
    @NotNull(message = "授权记录不能为空")
    @Valid
    private List<AgentAclItemBo> items;
}
