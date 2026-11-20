package com.campustrade.module.admin;

import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.common.R;
import com.campustrade.module.admin.AdminUserService.AdminUserDetail;
import com.campustrade.module.admin.AdminUserService.AdminUserVO;
import com.campustrade.module.admin.AdminUserService.UserQuery;
import com.campustrade.module.user.UserDtos.CreditLogVO;
import com.campustrade.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService service;

    public record PunishReq(Integer days, @NotBlank @Size(max = 200) String reason) {
    }

    public record CreditReq(@NotNull @Min(-100) @Max(100) Integer delta, @NotBlank @Size(max = 200) String reason) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('user:view')")
    public R<PageResult<AdminUserVO>> list(UserQuery q) {
        return R.ok(service.list(q));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:view')")
    public R<AdminUserDetail> detail(@PathVariable long id) {
        return R.ok(service.detail(id));
    }

    @GetMapping("/{id}/credit-logs")
    @PreAuthorize("hasAuthority('user:view')")
    public R<PageResult<CreditLogVO>> creditLogs(@PathVariable long id, PageQuery pq) {
        return R.ok(service.creditLogs(id, pq));
    }

    @PostMapping("/{id}/mute")
    @PreAuthorize("hasAuthority('user:punish')")
    @OpLog(module = "用户管理", action = "禁言")
    public R<Void> mute(@PathVariable long id, @Valid @RequestBody PunishReq req) {
        service.mute(id, req.days(), req.reason());
        return R.ok();
    }

    @PostMapping("/{id}/ban")
    @PreAuthorize("hasAuthority('user:punish')")
    @OpLog(module = "用户管理", action = "封禁")
    public R<Void> ban(@PathVariable long id, @Valid @RequestBody PunishReq req) {
        service.ban(id, req.days(), req.reason());
        return R.ok();
    }

    @PostMapping("/{id}/unban")
    @PreAuthorize("hasAuthority('user:punish')")
    @OpLog(module = "用户管理", action = "解除处罚")
    public R<Void> unban(@PathVariable long id) {
        service.unban(id);
        return R.ok();
    }

    @PostMapping("/{id}/credit")
    @PreAuthorize("hasAuthority('user:credit')")
    @OpLog(module = "用户管理", action = "调整信用分")
    public R<Void> credit(@PathVariable long id, @Valid @RequestBody CreditReq req) {
        service.adjustCredit(CurrentUser.id(), id, req.delta(), req.reason());
        return R.ok();
    }
}
