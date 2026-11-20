package com.campustrade.infra.mq;

import com.campustrade.config.AppProperties;

import java.time.Duration;
import java.util.function.Function;

/** 延时类型与对应时长（取自 app.rules） */
public enum DelayType {
    AUTO_CANCEL_UNCONFIRMED(AppProperties.Rules::faceConfirmTimeout),
    AUTO_CANCEL_UNPAID(AppProperties.Rules::payTimeout),
    AUTO_COMPLETE(AppProperties.Rules::faceAutoComplete),
    AUTO_CONFIRM(AppProperties.Rules::autoConfirm),
    AUTO_AGREE_CANCEL(AppProperties.Rules::afterSaleCancelTimeout),
    AUTO_AGREE_REFUND(AppProperties.Rules::afterSaleRefundTimeout),
    AUTO_CONFIRM_AFTER_REJECT(AppProperties.Rules::afterSaleAppealTimeout);

    private final Function<AppProperties.Rules, Duration> delay;

    DelayType(Function<AppProperties.Rules, Duration> delay) {
        this.delay = delay;
    }

    public Duration delay(AppProperties.Rules rules) {
        return delay.apply(rules);
    }

    /** 队列名带上 TTL，修改时长后会声明新队列，避免与已存在队列的参数冲突 */
    public String queueName(AppProperties.Rules rules) {
        return "ct.delay." + name().toLowerCase() + "." + delay(rules).toMillis();
    }
}
