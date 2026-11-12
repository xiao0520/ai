package org.ruoyi.integration.kafka;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.beans.factory.annotation.Qualifier;

import org.springframework.util.StringUtils;
import java.util.HashMap;
import java.util.Map;

/** 独立配置知识库 Kafka 客户端，不改变聊天 HTTP/SSE 的依赖和序列化。 */
@Configuration
@EnableKafka
@EnableScheduling
@ConditionalOnProperty(prefix = "knowledge.kafka", name = "enabled", havingValue = "true")
public class KnowledgeKafkaConfiguration {
    /** Broker 断开时 Outbox 保留消息；本地事务不与 Kafka 网络事务混合。 */
    @Bean
    public DefaultKafkaProducerFactory<String, byte[]> knowledgeKafkaProducerFactory(KnowledgeKafkaProperties properties) {
        Map<String, Object> config = base(properties);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, properties.getSendTimeoutSeconds() * 1000);
        return new DefaultKafkaProducerFactory<>(config);
    }

    /** 让 Spring 管理 ProducerFactory，确保停机关闭连接与发送线程。 */
    @Bean
    public KafkaTemplate<String, byte[]> knowledgeKafkaTemplate(
        @Qualifier("knowledgeKafkaProducerFactory") DefaultKafkaProducerFactory<String, byte[]> factory) {
        return new KafkaTemplate<>(factory);
    }

    /** 网络投递等待不能阻塞租约扫描，两个任务使用独立调度线程。 */
    @Bean
    public ThreadPoolTaskScheduler knowledgeKafkaScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("knowledge-kafka-");
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> knowledgeKafkaListenerContainerFactory(
        KnowledgeKafkaProperties properties) {
        Map<String, Object> config = base(properties);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // 数据库等基础设施异常停止分区消费，保留未确认结果，禁止默认恢复器跳过任务结果。
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }

    @Bean
    public KafkaAdmin knowledgeKafkaAdmin(KnowledgeKafkaProperties properties) {
        KafkaAdmin admin = new KafkaAdmin(base(properties));
        admin.setFatalIfBrokerNotAvailable(false);
        return admin;
    }

    @Bean
    public KafkaAdmin.NewTopics knowledgeKafkaTopics(KnowledgeKafkaProperties properties) {
        return new KafkaAdmin.NewTopics(topic(properties.getCommandTopic(), properties),
            topic(properties.getResultTopic(), properties), topic(properties.getControlTopic(), properties),
            topic(properties.getDlqTopic(), properties));
    }

    private NewTopic topic(String name, KnowledgeKafkaProperties properties) {
        return TopicBuilder.name(name).partitions(properties.getPartitions())
            .replicas(properties.getReplicationFactor()).config("retention.ms", "604800000").build();
    }

    private Map<String, Object> base(KnowledgeKafkaProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers());
        config.put("security.protocol", properties.getSecurityProtocol());
        config.put("sasl.mechanism", properties.getSaslMechanism());
        if (StringUtils.hasText(properties.getSaslJaasConfig())) config.put("sasl.jaas.config", properties.getSaslJaasConfig());
        if (StringUtils.hasText(properties.getSslTruststoreLocation())) config.put("ssl.truststore.location", properties.getSslTruststoreLocation());
        if (StringUtils.hasText(properties.getSslTruststorePassword())) config.put("ssl.truststore.password", properties.getSslTruststorePassword());
        return config;
    }
}