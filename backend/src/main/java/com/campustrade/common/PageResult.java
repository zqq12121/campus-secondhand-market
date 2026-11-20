package com.campustrade.common;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

public record PageResult<T>(List<T> list, int total, int page, int size) {

    public static <E, T> PageResult<T> of(IPage<E> page, Function<E, T> mapper) {
        return new PageResult<>(page.getRecords().stream().map(mapper).toList(),
                (int) page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    public static <T> PageResult<T> of(IPage<?> page, List<T> list) {
        return new PageResult<>(list, (int) page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }
}
