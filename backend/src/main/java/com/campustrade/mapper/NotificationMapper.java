package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.Notification;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface NotificationMapper extends BaseMapper<Notification> {

    /** 给全体未删除用户写一条系统通知，返回写入条数 */
    @Insert("""
            INSERT INTO notification (user_id, type, title, content, is_read, created_at)
            SELECT id, #{type}, #{title}, #{content}, 0, NOW() FROM `user` WHERE deleted = 0
            """)
    int insertForAllUsers(@Param("type") int type, @Param("title") String title, @Param("content") String content);
}
