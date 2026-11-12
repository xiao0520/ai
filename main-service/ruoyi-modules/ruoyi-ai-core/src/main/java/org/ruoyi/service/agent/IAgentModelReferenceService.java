package org.ruoyi.service.agent;

import java.util.Collection;

/**
 * Agent 对模型配置的引用校验服务。
 */
public interface IAgentModelReferenceService {

    /**
     * 校验模型未被 Agent 默认模型或可选模型绑定引用。
     *
     * @param modelIds 待校验的模型主键集合
     * @throws org.ruoyi.common.core.exception.ServiceException 任一模型仍被 Agent 引用时抛出
     */
    void requireModelsUnreferenced(Collection<Long> modelIds);
}