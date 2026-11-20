package com.campustrade.infra.mq;

import com.campustrade.common.TxUtil;
import com.campustrade.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** 事务提交后投递延时消息；投递失败只记日志，由定时扫描兜底 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelayPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final AppProperties props;

    public void schedule(DelayType type, long orderId, Long afterSaleId) {
        DelayMessage msg = new DelayMessage(orderId, type.name(), afterSaleId);
        String routingKey = type.queueName(props.rules());
        TxUtil.afterCommit(() -> {
            try {
                rabbitTemplate.convertAndSend(RabbitConfig.DELAY_EXCHANGE, routingKey, msg);
            } catch (AmqpException e) {
                log.warn("延时消息投递失败，等待定时扫描兜底 {}", msg, e);
            }
        });
    }
}
