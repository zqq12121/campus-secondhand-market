package com.campustrade.module.user;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.common.Consts.CreditReason;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.entity.CreditLog;
import com.campustrade.entity.User;
import com.campustrade.mapper.CreditLogMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.module.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;

/** 信用分变更：截断到 0–100，写流水并通知（PRD 第 7 章） */
@Service
@RequiredArgsConstructor
public class CreditService {

    private static final int MONTHLY_CAP = 10;
    private static final Map<Integer, String> REASON_TEXT = Map.of(
            CreditReason.TRADE_DONE, "完成交易", CreditReason.GOOD_REVIEW, "收到好评",
            CreditReason.BAD_REVIEW, "收到差评", CreditReason.DEFAULT_CANCEL, "违约取消/爽约",
            CreditReason.DISPUTE_LOST, "纠纷败诉", CreditReason.REPORT_ESTABLISHED, "举报成立",
            CreditReason.ADMIN_ADJUST, "管理员调整", CreditReason.APPEAL_PASSED, "申诉通过");

    private final UserMapper userMapper;
    private final CreditLogMapper creditLogMapper;
    private final NotificationService notificationService;

    public static String reasonText(int reasonType) {
        return REASON_TEXT.getOrDefault(reasonType, "其他");
    }

    /** 完成交易 / 收到好评：每月各自上限 +10 */
    @Transactional
    public void addWithMonthlyCap(long userId, int reasonType, Long refId, String remark) {
        QueryWrapper<CreditLog> q = new QueryWrapper<CreditLog>().select("IFNULL(SUM(delta),0) AS delta")
                .eq("user_id", userId).eq("reason_type", reasonType).gt("delta", 0)
                .ge("created_at", LocalDate.now().withDayOfMonth(1).atStartOfDay());
        Object sum = creditLogMapper.selectObjs(q).stream().findFirst().orElse(0);
        if (((Number) sum).intValue() >= MONTHLY_CAP) {
            return;
        }
        change(userId, 1, reasonType, refId, remark, null);
    }

    @Transactional
    public void change(long userId, int delta, int reasonType, Long refId, String remark, Long operatorId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return;
        }
        int after = Math.max(0, Math.min(100, user.getCreditScore() + delta));
        int actual = after - user.getCreditScore();
        if (actual == 0) {
            return;
        }
        userMapper.update(new LambdaUpdateWrapper<User>().eq(User::getId, userId).set(User::getCreditScore, after));
        CreditLog log = new CreditLog();
        log.setUserId(userId);
        log.setDelta(actual);
        log.setScoreAfter(after);
        log.setReasonType(reasonType);
        log.setRefId(refId);
        log.setRemark(remark);
        log.setOperatorId(operatorId);
        creditLogMapper.insert(log);
        notificationService.send(userId, NotifyType.CREDIT, "信用分变动",
                reasonText(reasonType) + "，信用分 " + (actual > 0 ? "+" : "") + actual + "，当前 " + after,
                null, null);
    }
}
