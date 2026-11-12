package org.ruoyi.domain.entity.model;

import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * 模型管理对象 model
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model")
public class Model extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 分类
     */
    private String category;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 提供者编码
     */
    private String providerCode;

    /**
     * 模型描述
     */
    private String modelDescribe;


    /**
     * 是否在用户端模型列表展示；AUTO 模式只选择可见模型
     */
    private String modelShow;

    /**
     * Embedding 输出向量维度，非向量模型为空
     */
    private Integer modelDimension;

    /**
     * 模型服务基础地址
     */
    private String apiHost;

    /**
     * 模型 API 协议模式，例如 CHAT_COMPLETIONS 或 RESPONSES
     */
    private String apiMode;

    /**
     * Python 端解析供应商密钥的凭证别名，不保存真实密钥
     */
    private String credentialKey;

    /**
     * 当前已发布配置修订号，用于执行快照一致性校验
     */
    private Integer publishedRevision;

    /**
     * 状态
     */
    private String status;

    /**
     * AUTO 路由能力标签 JSON 数组，例如 ["GENERAL","CODE"]
     */
    private String capabilities;

    /**
     * 输入模态 JSON 数组，例如 ["TEXT"] 或 ["TEXT","IMAGE"]
     */
    private String inputModalities;

    /**
     * Provider 适配器编码
     */
    private String adapterCode;

    /**
     * 向量是否已归一化
     */
    private Boolean normalized;

    /**
     * 模型完整上下文窗口 Token 上限
     */
    private Integer maxContextTokens;

    /**
     * 单次请求允许的最大输入 Token 数
     */
    private Integer maxInputTokens;

    /**
     * Rerank 单文档最大输入令牌数
     */
    private Integer maxDocumentTokens;

    /**
     * 单次请求允许的最大输出 Token 数
     */
    private Integer maxOutputTokens;

    /**
     * 上下文预算计算时预留的安全 Token 数
     */
    private Integer safetyMarginTokens;

    /**
     * 普通聊天最多携带的最近历史轮数
     */
    private Integer historyMaxTurns;

    /**
     * RAG 回答最多使用的知识片段数量
     */
    private Integer knowledgeMaxChunks;

    /**
     * RAG 知识片段允许占用的最大 Token 数
     */
    private Integer knowledgeMaxTokens;

    /**
     * 单次工具结果允许注入上下文的最大 Token 数
     */
    private Integer toolResultMaxTokens;

    /**
     * 该模型每分钟允许的最大请求数
     */
    private Integer rateLimitRpm;

    /**
     * 每百万输入 Token 成本，用于预算和 AUTO 兜底排序
     */
    private BigDecimal inputCostPerMillion;

    /**
     * 每百万输出 Token 成本，用于预算和 AUTO 兜底排序
     */
    private BigDecimal outputCostPerMillion;

    /**
     * AUTO 路由优先级，值越大越优先，服务端限制为 0 到 10000
     */
    private Integer grayWeight;

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
