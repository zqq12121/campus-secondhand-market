package com.campustrade.module.order;

import com.campustrade.infra.mq.DelayMessage;
import com.campustrade.infra.mq.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 消费到期的延时消息；处理失败只记日志不重回队列，由定时扫描兜底 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutListener {

    private final TimeoutService timeoutService;

    @RabbitListener(queues = RabbitConfig.TIMEOUT_QUEUE)
    public void onTimeout(DelayMessage m) {
        log.info("收到到期延时消息 {}", m);
        try {
            timeoutService.handle(m);
        } catch (RuntimeException e) {
            log.error("延时消息处理失败 {}", m, e);
        }
    }
}
