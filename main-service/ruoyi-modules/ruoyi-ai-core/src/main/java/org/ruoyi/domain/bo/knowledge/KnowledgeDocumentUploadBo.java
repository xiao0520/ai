package org.ruoyi.domain.bo.knowledge;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档或文档版本上传参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class KnowledgeDocumentUploadBo extends KnowledgeDocumentProcessingBo {
    /**
     * 知识库ID
     */
    private Long knowledgeId;
    /**
     * 是否自动解析
     */
    private Boolean autoParse = Boolean.TRUE;
    /**
     * 文件
     */
    private MultipartFile file;
}
