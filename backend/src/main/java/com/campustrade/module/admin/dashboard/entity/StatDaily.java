package com.campustrade.module.admin.dashboard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 数据看板日统计（定时任务每小时汇总当天） */
@Data
@TableName("stat_daily")
public class StatDaily {

    @TableId(type = IdType.INPUT)
    private LocalDate statDate;

    private Integer newUsers;

    private Integer newGoods;

    private Integer newOrders;

    private Integer finishedOrders;

    /** 当日完成订单金额 */
    private BigDecimal gmv;

    private LocalDateTime updatedAt;
}
