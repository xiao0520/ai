package org.ruoyi.domain.bo.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Date;

/**
 * 知识库 ACL 配置对象。
 */
@Data
public class KnowledgeAclBo {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 知识库ID
     */
    @NotNull(message = "知识库ID不能为空")
    private Long knowledgeId;
    /**
     * 主体类型
     */
    @NotBlank(message = "授权主体类型不能为空")
    private String subjectType;
    /**
     * 主体ID
     */
    @NotBlank(message = "授权主体不能为空")
    private String subjectId;
    /**
     * 权限
     */
    @NotBlank(message = "权限不能为空")
    private String permission;
    /**
     * 是否包含子级
     */
    private String includeChildren;
    /**
     * 状态
     */
    private String status;
    /**
     * 生效时间
     */
    private Date effectiveTime;
    /**
     * 过期时间
     */
    private Date expireTime;
}
