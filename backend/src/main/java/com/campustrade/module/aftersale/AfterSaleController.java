package com.campustrade.module.aftersale;

import com.campustrade.common.R;
import com.campustrade.module.aftersale.AfterSaleService.AfterSaleVO;
import com.campustrade.module.aftersale.AfterSaleService.ApplyReq;
import com.campustrade.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AfterSaleController {

    private final AfterSaleService service;

    public record RejectReq(@NotBlank @Size(max = 200) String reply) {
    }

    public record AppealReq(@Size(max = 200) String remark) {
    }

    @PostMapping("/orders/{orderId}/after-sales")
    public R<AfterSaleVO> apply(@PathVariable long orderId, @Valid @RequestBody ApplyReq req) {
        return R.ok(service.apply(CurrentUser.id(), orderId, req));
    }

    @GetMapping("/after-sales/{id}")
    public R<AfterSaleVO> detail(@PathVariable long id) {
        return R.ok(service.detail(CurrentUser.id(), id));
    }

    @PostMapping("/after-sales/{id}/agree")
    public R<Void> agree(@PathVariable long id) {
        service.agree(CurrentUser.id(), id);
        return R.ok();
    }

    @PostMapping("/after-sales/{id}/reject")
    public R<Void> reject(@PathVariable long id, @Valid @RequestBody RejectReq req) {
        service.reject(CurrentUser.id(), id, req.reply());
        return R.ok();
    }

    @PostMapping("/after-sales/{id}/appeal")
    public R<Void> appeal(@PathVariable long id, @Valid @RequestBody(required = false) AppealReq req) {
        service.appeal(CurrentUser.id(), id, req == null ? null : req.remark());
        return R.ok();
    }

    @PostMapping("/after-sales/{id}/withdraw")
    public R<Void> withdraw(@PathVariable long id) {
        service.withdraw(CurrentUser.id(), id);
        return R.ok();
    }
}
