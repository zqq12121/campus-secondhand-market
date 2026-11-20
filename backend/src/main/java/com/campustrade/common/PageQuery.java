package com.campustrade.common;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

/** 分页参数：page 从 1 开始，size 默认 20、最大 50 */
@Data
public class PageQuery {
    private Integer page = 1;
    private Integer size = 20;

    public <T> Page<T> toPage() {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 50);
        return Page.of(p, s);
    }
}
