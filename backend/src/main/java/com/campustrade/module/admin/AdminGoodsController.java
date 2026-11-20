package com.campustrade.module.admin;

import com.campustrade.common.Biz;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.common.R;
import com.campustrade.module.admin.AdminGoodsService.AdminGoodsDetail;
import com.campustrade.module.admin.AdminGoodsService.AdminGoodsVO;
import com.campustrade.module.admin.AdminGoodsService.AuditRecordVO;
import com.campustrade.module.admin.AdminGoodsService.BatchResult;
import com.campustrade.module.admin.AdminGoodsService.GoodsQuery;
import com.campustrade.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1")
@RequiredArgsConstructor
public class AdminGoodsController {

    private final AdminGoodsService service;

    public record ReasonReq(@NotBlank @Size(max = 200) String reason) {
    }

    public record BatchReq(@NotEmpty @Size(max = 100) List<Long> ids,
                           @NotBlank @Pattern(regexp = "APPROVE|REJECT") String result,
                           @Size(max = 200) String reason) {
    }

    @GetMapping("/audit/goods")
    @PreAuthorize("hasAuthority('goods:audit')")
    public R<PageResult<AdminGoodsVO>> pending(@RequestParam(required = false) Long categoryId,
                                               @RequestParam(required = false) String keyword, PageQuery pq) {
        return R.ok(service.pending(categoryId, keyword, pq));
    }

    @PostMapping("/audit/goods/{id}/approve")
    @PreAuthorize("hasAuthority('goods:audit')")
    @OpLog(module = "商品审核", action = "通过")
    public R<Void> approve(@PathVariable long id) {
        service.approve(CurrentUser.id(), id);
        return R.ok();
    }

    @PostMapping("/audit/goods/{id}/reject")
    @PreAuthorize("hasAuthority('goods:audit')")
    @OpLog(module = "商品审核", action = "驳回")
    public R<Void> reject(@PathVariable long id, @Valid @RequestBody ReasonReq req) {
        service.reject(CurrentUser.id(), id, req.reason());
        return R.ok();
    }

    @PostMapping("/audit/goods/batch")
    @PreAuthorize("hasAuthority('goods:audit')")
    @OpLog(module = "商品审核", action = "批量审核")
    public R<BatchResult> batch(@Valid @RequestBody BatchReq req) {
        boolean approve = "APPROVE".equals(req.result());
        Biz.check(approve || (req.reason() != null && !req.reason().isBlank()), ErrorCode.BAD_REQUEST, "驳回必须填写原因");
        return R.ok(service.batch(CurrentUser.id(), req.ids(), approve, req.reason()));
    }

    @GetMapping("/audit/goods/{id}/records")
    @PreAuthorize("hasAuthority('goods:audit')")
    public R<List<AuditRecordVO>> records(@PathVariable long id) {
        return R.ok(service.records(id));
    }

    @GetMapping("/goods")
    @PreAuthorize("hasAuthority('goods:manage')")
    public R<PageResult<AdminGoodsVO>> list(GoodsQuery q) {
        return R.ok(service.list(q));
    }

    @GetMapping("/goods/{id}")
    @PreAuthorize("hasAuthority('goods:manage')")
    public R<AdminGoodsDetail> detail(@PathVariable long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/goods/{id}/force-off")
    @PreAuthorize("hasAuthority('goods:manage')")
    @OpLog(module = "商品管理", action = "强制下架")
    public R<Void> forceOff(@PathVariable long id, @Valid @RequestBody ReasonReq req) {
        service.forceOff(CurrentUser.id(), id, req.reason());
        return R.ok();
    }
}
