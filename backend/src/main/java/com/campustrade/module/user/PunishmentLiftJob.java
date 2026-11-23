package com.campustrade.module.user;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.common.Consts.UserStatus;
import com.campustrade.module.user.entity.User;
import com.campustrade.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 定时任务：禁言 / 封禁到期自动恢复 */
@Component
@RequiredArgsConstructor
public class PunishmentLiftJob {

    private final UserMapper userMapper;

    /** 禁言 / 封禁到期自动恢复 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void liftPunishments() {
        userMapper.update(new LambdaUpdateWrapper<User>()
                .in(User::getStatus, UserStatus.MUTED, UserStatus.BANNED)
                .isNotNull(User::getBanUntil).lt(User::getBanUntil, LocalDateTime.now())
                .set(User::getStatus, UserStatus.NORMAL).set(User::getBanUntil, null));
    }
}
