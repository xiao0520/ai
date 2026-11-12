package org.ruoyi.domain.bo.mcp;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 手工导入 MCP tools/list Manifest。
 */
@Data
public class McpToolImportBo {

    /**
     * 清单JSON
     */
    @NotBlank(message = "Manifest JSON不能为空")
    private String manifestJson;
}
