package com.campustrade;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 鉴权走自定义 JWT 过滤器，关闭默认的内存用户；Mapper 分散在各模块的 mapper 包里，只注册继承 BaseMapper 的接口 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan(basePackages = "com.campustrade.module", markerInterface = BaseMapper.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class CampusTradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusTradeApplication.class, args);
    }
}
