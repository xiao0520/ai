package org.ruoyi.domain.vo.knowledge;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.ruoyi.domain.entity.knowledge.KnowledgeAcl;

import java.io.Serializable;
import java.util.Date;

/**
 * 知识库 ACL 视图。
 */
@Data
@AutoMapper(target = KnowledgeAcl.class)
public class KnowledgeAclVo implements Serializable {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 知识库ID
     */
    private Long knowledgeId;
    /**
     * 主体类型
     */
    private String subjectType;
    /**
     * 主体ID
     */
    private String subjectId;
    /**
     * 权限
     */
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
    /**
     * 创建时间
     */
    private Date createTime;
    /**
     * 更新时间
     */
    private Date updateTime;
}
