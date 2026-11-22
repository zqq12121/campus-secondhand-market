package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 用户 */
@Data
@TableName("`user`")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long schoolId;

    /** 所属校区 */
    private Long campusId;

    /** 校园邮箱，注册即认证 */
    private String email;

    /** BCrypt 哈希 */
    private String passwordHash;

    private String nickname;

    private String avatarUrl;

    /** 学院 */
    private String college;

    /** 个人简介 */
    private String bio;

    /** 信用分 0-100 */
    private Integer creditScore;

    /** 平台余额（担保交易放款，演示用不提现） */
    private BigDecimal balance;

    /** 0正常 1禁言 2封禁 */
    private Integer status;

    /** 禁言/封禁截止时间，NULL 表示永久或未处罚 */
    private LocalDateTime banUntil;

    private LocalDateTime lastLoginAt;

    /** 1 开启邮件通知：订单、审核、举报结果、信用分通知同时发邮件 */
    private Integer emailNotify;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
