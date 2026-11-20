package com.campustrade.infra.mq;

import com.campustrade.config.AppProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 延时队列：消息投递到 ct.delay（direct），按类型路由到带 TTL 的队列；
 * 到期后死信转到 ct.timeout → ct.timeout.q，由 TimeoutListener 消费。
 */
@Configuration
public class RabbitConfig {

    public static final String DELAY_EXCHANGE = "ct.delay";
    public static final String TIMEOUT_EXCHANGE = "ct.timeout";
    public static final String TIMEOUT_QUEUE = "ct.timeout.q";
    public static final String TIMEOUT_KEY = "timeout";

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public Declarables delayTopology(AppProperties props) {
        DirectExchange delay = new DirectExchange(DELAY_EXCHANGE, true, false);
        DirectExchange timeout = new DirectExchange(TIMEOUT_EXCHANGE, true, false);
        Queue timeoutQueue = QueueBuilder.durable(TIMEOUT_QUEUE).build();
        List<Declarable> list = new ArrayList<>(List.of(delay, timeout, timeoutQueue,
                BindingBuilder.bind(timeoutQueue).to(timeout).with(TIMEOUT_KEY)));
        for (DelayType t : DelayType.values()) {
            Queue q = QueueBuilder.durable(t.queueName(props.rules()))
                    .ttl((int) t.delay(props.rules()).toMillis())
                    .deadLetterExchange(TIMEOUT_EXCHANGE).deadLetterRoutingKey(TIMEOUT_KEY).build();
            // 路由键用队列名（含 TTL）：改时长后旧队列不再收到消息，避免同一消息被复制到新旧两个队列
            Binding b = BindingBuilder.bind(q).to(delay).with(q.getName());
            list.add(q);
            list.add(b);
        }
        return new Declarables(list);
    }
}
