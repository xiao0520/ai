package org.ruoyi.domain.entity.knowledge;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 知识库对象 knowledge_info
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_info")
public class KnowledgeInfo extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 知识库编码
     */
    private String libraryCode;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 名称
     */
    private String name;

    /**
     * 访问范围
     */
    private String accessScope;

    /**
     * 描述
     */
    private String description;

    /**
     * 分割器配置
     */
    private String splitterConfig;

    /** 查询时的检索策略 JSON；空值表示存量兼容策略。 */
    private String retrievalConfig;

    /**
     * 当前索引版本
     */
    private String currentIndexVersion;

    /**
     * 权限修订号
     */
    private Integer permissionRevision;

    /**
     * 状态
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 删除标志
     */
    @TableLogic
    private String delFlag;

}
