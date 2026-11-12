package org.ruoyi.domain.entity.knowledge;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.util.Date;

/**
 * 知识库资源级 ACL。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_acl")
public class KnowledgeAcl extends BaseEntity {
    /**
     * 主键ID
     */
    @TableId("id")
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
}
