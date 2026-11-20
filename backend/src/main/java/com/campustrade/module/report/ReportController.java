package com.campustrade.module.report;

import com.campustrade.common.R;
import com.campustrade.module.report.ReportService.CreateReq;
import com.campustrade.module.report.ReportService.IdResp;
import com.campustrade.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 举报（接口文档 §12） */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping("/reports")
    public R<IdResp> create(@Valid @RequestBody CreateReq req) {
        return R.ok(reportService.create(CurrentUser.id(), req));
    }
}
