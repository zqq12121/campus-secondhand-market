package com.campustrade.module.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.Biz;
import com.campustrade.common.Consts.ReportTarget;
import com.campustrade.common.ErrorCode;
import com.campustrade.infra.FileStorageService;
import com.campustrade.module.goods.entity.Goods;
import com.campustrade.module.goods.mapper.GoodsMapper;
import com.campustrade.module.report.entity.Report;
import com.campustrade.module.report.mapper.ReportMapper;
import com.campustrade.module.user.entity.User;
import com.campustrade.module.user.mapper.UserMapper;
import com.campustrade.module.want.entity.WantPost;
import com.campustrade.module.want.mapper.WantPostMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportService {

    private static final int REASON_OTHER = 5;

    private final ReportMapper reportMapper;
    private final GoodsMapper goodsMapper;
    private final UserMapper userMapper;
    private final WantPostMapper wantMapper;
    private final FileStorageService fileStorageService;

    public record CreateReq(@NotNull @Min(1) @Max(3) Integer targetType,
                            @NotNull Long targetId,
                            @NotNull @Min(1) @Max(5) Integer reasonType,
                            @Size(max = 500, message = "说明不超过 500 字") String description,
                            @Size(max = 6, message = "凭证最多 6 张") List<String> evidenceUrls) {
    }

    public record IdResp(Long id) {
    }

    /** 提交举报（接口文档 12.1）；处理结果通过通知（type=4）告知 */
    public IdResp create(long userId, CreateReq req) {
        String desc = req.description() == null ? "" : req.description().trim();
        Biz.check(req.reasonType() != REASON_OTHER || !desc.isEmpty(), ErrorCode.BAD_REQUEST, "选择「其他」时请填写说明");
        List<String> evidence = req.evidenceUrls() == null ? List.of() : req.evidenceUrls();
        for (String url : evidence) {
            Biz.check(fileStorageService.isOwnUrl(url), ErrorCode.BAD_REQUEST, "图片必须通过本平台上传");
        }
        Long owner = ownerOf(req.targetType(), req.targetId());
        Biz.check(!owner.equals(userId), ErrorCode.REPORT_SELF);
        boolean recent = reportMapper.exists(new LambdaQueryWrapper<Report>().eq(Report::getReporterId, userId)
                .eq(Report::getTargetType, req.targetType()).eq(Report::getTargetId, req.targetId())
                .gt(Report::getCreatedAt, LocalDateTime.now().minusHours(24)));
        Biz.check(!recent, ErrorCode.REPORT_DUPLICATED);

        Report r = new Report();
        r.setReporterId(userId);
        r.setTargetType(req.targetType());
        r.setTargetId(req.targetId());
        r.setReasonType(req.reasonType());
        r.setDescription(desc.isEmpty() ? null : desc);
        r.setEvidenceUrls(evidence);
        r.setStatus(0);
        reportMapper.insert(r);
        return new IdResp(r.getId());
    }

    /** 被举报对象的所有者（商品卖家 / 用户本人 / 求购发布者）；对象不存在时抛 404 */
    private Long ownerOf(int targetType, long targetId) {
        Long owner = switch (targetType) {
            case ReportTarget.GOODS -> {
                Goods g = goodsMapper.selectById(targetId);
                yield g == null ? null : g.getSellerId();
            }
            case ReportTarget.USER -> {
                User u = userMapper.selectById(targetId);
                yield u == null ? null : u.getId();
            }
            case ReportTarget.WANT -> {
                WantPost w = wantMapper.selectById(targetId);
                yield w == null ? null : w.getUserId();
            }
            default -> null;
        };
        return Biz.notNull(owner, ErrorCode.NOT_FOUND);
    }
}
