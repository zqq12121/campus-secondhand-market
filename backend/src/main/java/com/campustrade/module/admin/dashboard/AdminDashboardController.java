package com.campustrade.module.admin.dashboard;

import com.campustrade.common.R;
import com.campustrade.module.admin.dashboard.AdminDashboardService.CategoryShare;
import com.campustrade.module.admin.dashboard.AdminDashboardService.Overview;
import com.campustrade.module.admin.dashboard.AdminDashboardService.Trend;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 数据看板（接口文档 13.2） */
@RestController
@RequestMapping("/api/admin/v1/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('dashboard:view')")
public class AdminDashboardController {

    private final AdminDashboardService service;

    @GetMapping("/overview")
    public R<Overview> overview() {
        return R.ok(service.overview());
    }

    /** days 取值 1–90，超出按边界处理 */
    @GetMapping("/trend")
    public R<Trend> trend(@RequestParam(defaultValue = "30") int days) {
        return R.ok(service.trend(days));
    }

    @GetMapping("/category-share")
    public R<List<CategoryShare>> categoryShare() {
        return R.ok(service.categoryShare());
    }
}
