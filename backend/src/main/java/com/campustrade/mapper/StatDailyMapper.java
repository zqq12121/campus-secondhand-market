package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.StatDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

public interface StatDailyMapper extends BaseMapper<StatDaily> {

    /** 重新汇总某一天并写入（已存在则覆盖） */
    @Insert("""
            INSERT INTO stat_daily (stat_date, new_users, new_goods, new_orders, finished_orders, gmv)
            SELECT #{d},
              (SELECT COUNT(*) FROM `user` WHERE deleted = 0 AND created_at >= #{d} AND created_at < #{next}),
              (SELECT COUNT(*) FROM goods WHERE deleted = 0 AND created_at >= #{d} AND created_at < #{next}),
              (SELECT COUNT(*) FROM trade_order WHERE created_at >= #{d} AND created_at < #{next}),
              (SELECT COUNT(*) FROM trade_order WHERE status = 70 AND finished_at >= #{d} AND finished_at < #{next}),
              (SELECT COALESCE(SUM(total_amount), 0) FROM trade_order
                 WHERE status = 70 AND finished_at >= #{d} AND finished_at < #{next})
            ON DUPLICATE KEY UPDATE new_users = VALUES(new_users), new_goods = VALUES(new_goods),
              new_orders = VALUES(new_orders), finished_orders = VALUES(finished_orders), gmv = VALUES(gmv),
              updated_at = NOW()
            """)
    void refresh(@Param("d") LocalDate day, @Param("next") LocalDate next);
}
