package org.ruoyi.domain.entity.knowledge;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** Java 已验收并纳入指定索引版本的文档成员。 */
@Data
@TableName("knowledge_index_document")
public class KnowledgeIndexDocument {
    @TableId("id")
    private Long id;
    private Long knowledgeId;
    private Long indexVersionId;
    private Long documentId;
    private Long documentVersionId;
    private Long generationId;
    private String manifestHash;
    private Integer chunkCount;
    private Integer memberRevision;
    private Date createTime;
    private Date updateTime;
}
