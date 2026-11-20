package com.campustrade.infra;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** 敏感词变更后经 Redis 发布消息，各实例收到后重建 DFA（含发布者自身） */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SensitiveWordSync {

    static final String CHANNEL = "sensitive-word:reload";

    private final StringRedisTemplate redis;

    public void publishReload() {
        redis.convertAndSend(CHANNEL, "reload");
    }

    @Bean
    RedisMessageListenerContainer sensitiveWordListener(RedisConnectionFactory factory, SensitiveWordService service) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener((message, pattern) -> {
            service.reload();
            log.info("敏感词库已重建");
        }, new ChannelTopic(CHANNEL));
        return container;
    }
}
