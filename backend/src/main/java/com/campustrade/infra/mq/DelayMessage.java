package com.campustrade.infra.mq;

/** 延时消息：到期后由 TimeoutService 再次校验状态与截止时间再处理 */
public record DelayMessage(Long orderId, String action, Long afterSaleId) {
}
