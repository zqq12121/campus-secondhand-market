package com.campustrade.module.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Biz;
import com.campustrade.common.BizException;
import com.campustrade.common.Consts.CreditReason;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.entity.CreditAppeal;
import com.campustrade.entity.CreditLog;
import com.campustrade.entity.User;
import com.campustrade.mapper.CreditAppealMapper;
import com.campustrade.mapper.CreditLogMapper;
import com.campustrade.module.notification.NotificationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 信用分申诉（P2）：用户对 30 天内的扣分记录申诉一次，管理员通过后加回扣掉的分数 */
@Service
@RequiredArgsConstructor
public class CreditAppealService {

    public static final int PENDING = 0;
    public static final int APPROVED = 1;
    public static final int REJECTED = 2;
    static final int APPEAL_DAYS = 30;

    private final CreditAppealMapper appealMapper;
    private final CreditLogMapper creditLogMapper;
    private final CreditService creditService;
    private final UserService userService;
    private final NotificationService notificationService;

    public record ApplyReq(@NotBlank @Size(max = 500, message = "申诉理由不超过 500 字") String reason) {
    }

    public record HandleReq(@NotNull Boolean approve, @NotBlank @Size(max = 200) String reply) {
    }

    public record AppealVO(Long id, Long creditLogId, Integer delta, String reasonText, String reason, Integer status,
                           String reply, LocalDateTime handledAt, LocalDateTime createdAt) {
    }

    public record AdminAppealVO(Long id, Long userId, String nickname, Integer creditScore, Long creditLogId,
                                Integer delta, String reasonText, String logRemark, LocalDateTime logCreatedAt,
                                String reason, Integer status, String reply, Long handlerId, LocalDateTime handledAt,
                                LocalDateTime createdAt) {
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class AdminQuery extends PageQuery {
        private Integer status;
    }

    // ---------------------------------------------------------------- 用户

    @Transactional
    public AppealVO apply(long userId, long creditLogId, ApplyReq req) {
        CreditLog log = creditLogMapper.selectById(creditLogId);
        Biz.check(log != null && log.getUserId() == userId && log.getDelta() < 0
                        && log.getReasonType() != CreditReason.APPEAL_PASSED
                        && log.getCreatedAt().isAfter(LocalDateTime.now().minusDays(APPEAL_DAYS)),
                ErrorCode.CREDIT_APPEAL_NOT_ALLOWED);
        CreditAppeal a = new CreditAppeal();
        a.setUserId(userId);
        a.setCreditLogId(creditLogId);
        a.setDelta(log.getDelta());
        a.setReason(req.reason().trim());
        a.setStatus(PENDING);
        a.setCreatedAt(LocalDateTime.now());
        try {
            appealMapper.insert(a);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CREDIT_APPEAL_EXISTS);
        }
        return toVO(a, log);
    }

    public PageResult<AppealVO> mine(long userId, PageQuery pq) {
        Page<CreditAppeal> page = appealMapper.selectPage(pq.toPage(), new LambdaQueryWrapper<CreditAppeal>()
                .eq(CreditAppeal::getUserId, userId).orderByDesc(CreditAppeal::getId));
        Map<Long, CreditLog> logs = logs(page.getRecords().stream().map(CreditAppeal::getCreditLogId).toList());
        return PageResult.of(page, a -> toVO(a, logs.get(a.getCreditLogId())));
    }

    // ---------------------------------------------------------------- 管理员

    public PageResult<AdminAppealVO> list(AdminQuery q) {
        Page<CreditAppeal> page = appealMapper.selectPage(q.toPage(), new LambdaQueryWrapper<CreditAppeal>()
                .eq(q.getStatus() != null, CreditAppeal::getStatus, q.getStatus()).orderByDesc(CreditAppeal::getId));
        List<CreditAppeal> rows = page.getRecords();
        Map<Long, CreditLog> logs = logs(rows.stream().map(CreditAppeal::getCreditLogId).toList());
        Map<Long, User> users = userService.mapByIds(rows.stream().map(CreditAppeal::getUserId).distinct().toList());
        return PageResult.of(page, a -> {
            CreditLog l = logs.get(a.getCreditLogId());
            User u = users.get(a.getUserId());
            return new AdminAppealVO(a.getId(), a.getUserId(), u == null ? null : u.getNickname(),
                    u == null ? null : u.getCreditScore(), a.getCreditLogId(), a.getDelta(),
                    l == null ? null : CreditService.reasonText(l.getReasonType()), l == null ? null : l.getRemark(),
                    l == null ? null : l.getCreatedAt(), a.getReason(), a.getStatus(), a.getReply(), a.getHandlerId(),
                    a.getHandledAt(), a.getCreatedAt());
        });
    }

    @Transactional
    public void handle(long adminId, long id, HandleReq req) {
        CreditAppeal a = Biz.notNull(appealMapper.selectById(id), ErrorCode.NOT_FOUND);
        Biz.check(a.getStatus() == PENDING, ErrorCode.CONFLICT, "申诉已处理");
        int to = req.approve() ? APPROVED : REJECTED;
        String reply = req.reply().trim();
        int n = appealMapper.update(new LambdaUpdateWrapper<CreditAppeal>().eq(CreditAppeal::getId, id)
                .eq(CreditAppeal::getStatus, PENDING).set(CreditAppeal::getStatus, to)
                .set(CreditAppeal::getHandlerId, adminId).set(CreditAppeal::getReply, reply)
                .set(CreditAppeal::getHandledAt, LocalDateTime.now()));
        Biz.check(n == 1, ErrorCode.CONFLICT);
        if (req.approve()) {
            // change 内部会发「信用分变动」通知
            creditService.change(a.getUserId(), -a.getDelta(), CreditReason.APPEAL_PASSED, a.getId(),
                    "信用分申诉通过：" + reply, adminId);
        } else {
            notificationService.send(a.getUserId(), NotifyType.CREDIT, "信用分申诉未通过", reply, null, null);
        }
    }

    // ---------------------------------------------------------------- 内部

    private Map<Long, CreditLog> logs(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return creditLogMapper.selectByIds(ids).stream().collect(Collectors.toMap(CreditLog::getId, l -> l));
    }

    private static AppealVO toVO(CreditAppeal a, CreditLog l) {
        return new AppealVO(a.getId(), a.getCreditLogId(), a.getDelta(),
                l == null ? null : CreditService.reasonText(l.getReasonType()), a.getReason(), a.getStatus(),
                a.getReply(), a.getHandledAt(), a.getCreatedAt());
    }
}
