package org.ruoyi.config;

import org.ruoyi.support.ChatExecutionProperties;
import org.ruoyi.support.KnowledgeRetrievalScopeProperties;
import org.ruoyi.support.RagChatProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 AI 执行服务配置，缺失内部凭证时启动即失败。 */
@Configuration
@EnableConfigurationProperties({ChatExecutionProperties.class, KnowledgeRetrievalScopeProperties.class, RagChatProperties.class})
public class ChatExecutionConfiguration {
}
