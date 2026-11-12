package org.ruoyi.config;

import org.ruoyi.support.InternalWorkerAuthProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 仅在启用 Python 读取受控内部资源时校验其独立服务身份，避免影响现有聊天部署。 */
@Configuration
@ConditionalOnProperty(prefix = "knowledge.worker-auth", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(InternalWorkerAuthProperties.class)
public class InternalWorkerAuthConfiguration {
}
