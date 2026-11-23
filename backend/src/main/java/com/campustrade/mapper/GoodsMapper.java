package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.Goods;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface GoodsMapper extends BaseMapper<Goods> {

    /**
     * 检索索引增量同步：按 (updated_at, id) 游标读取变更的商品，包含已逻辑删除的行（需要从索引删除）。
     * 自定义 SQL 不会被 @TableLogic 自动加上 deleted = 0。
     */
    @Select("""
            SELECT * FROM goods
            WHERE updated_at > #{since} OR (updated_at = #{since} AND id > #{afterId})
            ORDER BY updated_at, id LIMIT #{limit}
            """)
    List<Goods> selectChangedSince(@Param("since") LocalDateTime since, @Param("afterId") long afterId,
                                   @Param("limit") int limit);
}
