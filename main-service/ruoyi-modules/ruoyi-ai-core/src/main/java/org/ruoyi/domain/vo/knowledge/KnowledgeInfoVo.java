package org.ruoyi.domain.vo.knowledge;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.ruoyi.domain.entity.knowledge.KnowledgeInfo;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;



/**
 * 知识库视图对象 knowledge_info
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = KnowledgeInfo.class)
public class KnowledgeInfoVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @ExcelProperty(value = "主键")
    private Long id;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 知识库编码
     */
    private String libraryCode;

    /**
     * 用户ID
     */
    @ExcelProperty(value = "用户ID")
    private Long userId;

    /**
     * 名称
     */
    @ExcelProperty(value = "知识库名称")
    private String name;

    /**
     * 访问范围
     */
    private String accessScope;

    /**
     * 描述
     */
    @ExcelProperty(value = "知识库描述")
    private String description;

    /**
     * 分割器配置
     */
    private String splitterConfig;

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
     * 文档数量
     */
    @ExcelProperty(value = "文档数量")
    private Integer documentCount;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;


}

