package com.campustrade.module.admin.report;

import com.campustrade.common.PageResult;
import com.campustrade.common.R;
import com.campustrade.module.admin.OpLog;
import com.campustrade.module.admin.report.AdminReportService.HandleReq;
import com.campustrade.module.admin.report.AdminReportService.HandleResp;
import com.campustrade.module.admin.report.AdminReportService.ReportDetail;
import com.campustrade.module.admin.report.AdminReportService.ReportItem;
import com.campustrade.module.admin.report.AdminReportService.ReportQuery;
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

/** 举报处理（接口文档 13.8） */
@RestController
@RequestMapping("/api/admin/v1/reports")
@RequiredArgsConstructor
public class AdminReportController {

    private final AdminReportService service;

    @GetMapping
    @PreAuthorize("hasAuthority('report:handle')")
    public R<PageResult<ReportItem>> list(ReportQuery q) {
        return R.ok(service.list(q));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('report:handle')")
    public R<ReportDetail> detail(@PathVariable long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/{id}/handle")
    @PreAuthorize("hasAuthority('report:handle')")
    @OpLog(module = "举报处理", action = "处理")
    public R<HandleResp> handle(@PathVariable long id, @Valid @RequestBody HandleReq req) {
        return R.ok(service.handle(CurrentUser.id(), id, req));
    }
}
