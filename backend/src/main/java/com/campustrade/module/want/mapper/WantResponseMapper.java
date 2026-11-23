package com.campustrade.module.want.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.module.want.entity.WantResponse;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface WantResponseMapper extends BaseMapper<WantResponse> {

    /** 重复响应直接忽略 */
    @Insert("INSERT IGNORE INTO want_response (want_id, responder_id) VALUES (#{wantId}, #{responderId})")
    int insertIgnore(@Param("wantId") long wantId, @Param("responderId") long responderId);
}
