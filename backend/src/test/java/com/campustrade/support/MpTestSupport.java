package com.campustrade.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/** 纯单元测试里使用 Lambda 条件构造器前，需先注册实体的表元数据 */
public final class MpTestSupport {

    private MpTestSupport() {
    }

    public static void initTables(Class<?>... entities) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> e : entities) {
            if (TableInfoHelper.getTableInfo(e) == null) {
                TableInfoHelper.initTableInfo(assistant, e);
            }
        }
    }
}
