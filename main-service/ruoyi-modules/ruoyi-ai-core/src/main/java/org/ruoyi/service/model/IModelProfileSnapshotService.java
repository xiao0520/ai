package org.ruoyi.service.model;

/**
 * 向其他领域提供不可变的模型发布快照，不暴露可变模型主表或 Mapper。
 */
public interface IModelProfileSnapshotService {

    /**
     * 读取模型的当前发布修订，并确认模型类型。
     *
     * @param modelId 模型主键
     * @param expectedCategory 期望的模型分类
     * @return 当前模型发布快照
     * @throws org.ruoyi.common.core.exception.ServiceException 模型不存在、未发布或分类不匹配时抛出
     */
    ModelProfileSnapshot requireCurrent(Long modelId, String expectedCategory);

    /**
     * 读取精确发布修订；修订缺失时禁止回退到可变模型主表。
     *
     * @param modelId 模型主键
     * @param revision 发布修订号
     * @param expectedCategory 期望的模型分类
     * @return 指定修订的模型发布快照
     * @throws org.ruoyi.common.core.exception.ServiceException 修订不存在或分类不匹配时抛出
     */
    ModelProfileSnapshot requireRevision(Long modelId, Integer revision, String expectedCategory);

    /**
     * 可供执行端使用的不可变模型配置。
     *
     * @param id 模型主键
     * @param revision 发布修订号
     * @param category 模型分类
     * @param providerCode 厂商编码
     * @param modelName 厂商侧模型名称
     * @param apiHost API 地址
     * @param apiMode API 协议模式
     * @param credentialKey Python 凭证别名
     * @param inputModalities 支持的输入模态
     * @param adapterCode 执行端适配器编码
     * @param dimensions 向量维度
     * @param normalized 是否输出归一化向量
     * @param maxInputTokens 最大输入 Token 数
     * @param maxDocumentTokens 单文档最大 Token 数
     */
    record ModelProfileSnapshot(Long id, Integer revision, String category, String providerCode,
                                String modelName, String apiHost, String apiMode, String credentialKey,
                                String inputModalities, String adapterCode, Integer dimensions,
                                Boolean normalized, Integer maxInputTokens, Integer maxDocumentTokens) {
    }
}