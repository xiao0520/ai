package org.ruoyi.domain.vo.model;


import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.ruoyi.domain.entity.model.Model;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 模型管理视图对象 model
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = Model.class)
public class ModelVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @ExcelProperty(value = "主键")
    private Long id;

    /**
     * 分类
     */
    @ExcelProperty(value = "模型分类")
    private String category;

    /**
     * 模型名称
     */
    @ExcelProperty(value = "模型名称")
    private String modelName;

    /**
     * 提供者编码
     */
    @ExcelProperty(value = "模型供应商")
    private String providerCode;

    /**
     * 模型描述
     */
    @ExcelProperty(value = "模型描述")
    private String modelDescribe;


    /**
     * 是否在用户端模型列表展示；AUTO 模式只选择可见模型
     */
    @ExcelProperty(value = "是否显示")
    private String modelShow;

    /**
     * Embedding 输出向量维度，非向量模型为空
     */
    @ExcelProperty(value = "向量维度")
    private Integer modelDimension;

    /**
     * 模型服务基础地址
     */
    @ExcelProperty(value = "请求地址")
    private String apiHost;

    /**
     * 模型 API 协议模式，例如 CHAT_COMPLETIONS 或 RESPONSES
     */
    @ExcelProperty(value = "API调用模式")
    private String apiMode;

    /**
     * Python 端解析供应商密钥的凭证别名，不保存真实密钥
     */
    @ExcelProperty(value = "凭证别名")
    private String credentialKey;

    /**
     * 当前已发布配置修订号，用于执行快照一致性校验
     */
    @ExcelProperty(value = "发布修订号")
    private Integer publishedRevision;

    /**
     * 状态
     */
    @ExcelProperty(value = "状态")
    private String status;

    /**
     * AUTO 路由能力标签 JSON 数组，例如 ["GENERAL","CODE"]
     */
    @ExcelProperty(value = "能力标签")
    private String capabilities;

    /**
     * 输入模态 JSON 数组
     */
    @ExcelProperty(value = "输入模态")
    private String inputModalities;

    /**
     * Provider 适配器编码
     */
    @ExcelProperty(value = "适配器编码")
    private String adapterCode;

    /**
     * 向量是否已归一化
     */
    @ExcelProperty(value = "向量归一化")
    private Boolean normalized;

    /**
     * 模型完整上下文窗口 Token 上限
     */
    @ExcelProperty(value = "最大上下文Token")
    private Integer maxContextTokens;

    /**
     * 单次请求允许的最大输入 Token 数
     */
    @ExcelProperty(value = "最大输入Token")
    private Integer maxInputTokens;

    /**
     * Rerank 单文档最大输入令牌数
     */
    @ExcelProperty(value = "最大文档Token")
    private Integer maxDocumentTokens;

    /**
     * 单次请求允许的最大输出 Token 数
     */
    @ExcelProperty(value = "最大输出Token")
    private Integer maxOutputTokens;

    /**
     * 上下文预算计算时预留的安全 Token 数
     */
    @ExcelProperty(value = "上下文安全余量Token")
    private Integer safetyMarginTokens;

    /**
     * 普通聊天最多携带的最近历史轮数
     */
    @ExcelProperty(value = "最近历史最大轮数")
    private Integer historyMaxTurns;

    /**
     * RAG 回答最多使用的知识片段数量
     */
    @ExcelProperty(value = "知识库最大检索片段数")
    private Integer knowledgeMaxChunks;

    /**
     * RAG 知识片段允许占用的最大 Token 数
     */
    @ExcelProperty(value = "知识库资料最大Token")
    private Integer knowledgeMaxTokens;

    /**
     * 单次工具结果允许注入上下文的最大 Token 数
     */
    @ExcelProperty(value = "单次工具结果最大Token")
    private Integer toolResultMaxTokens;

    /**
     * 该模型每分钟允许的最大请求数
     */
    @ExcelProperty(value = "每分钟请求限制")
    private Integer rateLimitRpm;

    /**
     * 每百万输入 Token 成本，用于预算和 AUTO 兜底排序
     */
    @ExcelProperty(value = "输入成本/百万Token")
    private BigDecimal inputCostPerMillion;

    /**
     * 每百万输出 Token 成本，用于预算和 AUTO 兜底排序
     */
    @ExcelProperty(value = "输出成本/百万Token")
    private BigDecimal outputCostPerMillion;

    /**
     * AUTO 路由优先级，值越大越优先，服务端限制为 0 到 10000
     */
    @ExcelProperty(value = "灰度权重")
    private Integer grayWeight;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;

}
