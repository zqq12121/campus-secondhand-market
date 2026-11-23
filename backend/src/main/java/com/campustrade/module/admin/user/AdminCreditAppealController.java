package com.campustrade.module.admin.user;

import com.campustrade.common.PageResult;
import com.campustrade.common.R;
import com.campustrade.module.admin.OpLog;
import com.campustrade.module.user.CreditAppealService.AdminAppealVO;
import com.campustrade.module.user.CreditAppealService.AdminQuery;
import com.campustrade.module.user.CreditAppealService.HandleReq;
import com.campustrade.module.user.CreditAppealService;
import com.campustrade.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 信用分申诉处理（P2） */
@RestController
@RequestMapping("/api/admin/v1/credit-appeals")
@RequiredArgsConstructor
public class AdminCreditAppealController {

    private final CreditAppealService service;

    @GetMapping
    @PreAuthorize("hasAuthority('user:credit-appeal')")
    public R<PageResult<AdminAppealVO>> list(AdminQuery q) {
        return R.ok(service.list(q));
    }

    @PostMapping("/{id}/handle")
    @PreAuthorize("hasAuthority('user:credit-appeal')")
    @OpLog(module = "信用分申诉", action = "处理")
    public R<Void> handle(@PathVariable long id, @Valid @RequestBody HandleReq req) {
        service.handle(CurrentUser.id(), id, req);
        return R.ok();
    }
}
