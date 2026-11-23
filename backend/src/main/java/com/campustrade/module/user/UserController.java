package com.campustrade.module.user;

import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.common.R;
import com.campustrade.module.auth.AuthDtos.LoginResp;
import com.campustrade.module.user.UserDtos.BalanceLogVO;
import com.campustrade.module.user.UserDtos.BlockVO;
import com.campustrade.module.user.UserDtos.ChangePasswordReq;
import com.campustrade.module.user.UserDtos.CreditLogVO;
import com.campustrade.module.user.UserDtos.MeVO;
import com.campustrade.module.user.UserDtos.ProfileVO;
import com.campustrade.module.user.UserDtos.UpdateMeReq;
import com.campustrade.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final FollowService followService;
    private final CreditAppealService creditAppealService;

    @GetMapping("/me")
    public R<MeVO> me() {
        return R.ok(userService.me(CurrentUser.id()));
    }

    @PutMapping("/me")
    public R<MeVO> updateMe(@Valid @RequestBody UpdateMeReq req) {
        return R.ok(userService.updateMe(CurrentUser.id(), req));
    }

    @PutMapping("/me/password")
    public R<LoginResp> changePassword(@Valid @RequestBody ChangePasswordReq req) {
        return R.ok(userService.changePassword(CurrentUser.id(), req.oldPassword(), req.newPassword()));
    }

    @GetMapping("/me/credit-logs")
    public R<PageResult<CreditLogVO>> creditLogs(PageQuery pq) {
        return R.ok(userService.creditLogs(CurrentUser.id(), pq));
    }

    @PostMapping("/me/credit-logs/{id}/appeal")
    public R<CreditAppealService.AppealVO> appeal(@PathVariable long id,
                                                  @Valid @RequestBody CreditAppealService.ApplyReq req) {
        return R.ok(creditAppealService.apply(CurrentUser.id(), id, req));
    }

    @GetMapping("/me/credit-appeals")
    public R<PageResult<CreditAppealService.AppealVO>> appeals(PageQuery pq) {
        return R.ok(creditAppealService.mine(CurrentUser.id(), pq));
    }

    @GetMapping("/me/balance-logs")
    public R<PageResult<BalanceLogVO>> balanceLogs(PageQuery pq) {
        return R.ok(userService.balanceLogs(CurrentUser.id(), pq));
    }

    @GetMapping("/me/blocks")
    public R<PageResult<BlockVO>> blocks(PageQuery pq) {
        return R.ok(userService.blocks(CurrentUser.id(), pq));
    }

    @PostMapping("/{id}/block")
    public R<Void> block(@PathVariable long id) {
        userService.block(CurrentUser.id(), id);
        return R.ok();
    }

    @DeleteMapping("/{id}/block")
    public R<Void> unblock(@PathVariable long id) {
        userService.unblock(CurrentUser.id(), id);
        return R.ok();
    }

    @GetMapping("/me/follows")
    public R<PageResult<FollowService.FollowVO>> follows(PageQuery pq) {
        return R.ok(followService.mine(CurrentUser.id(), pq));
    }

    @PostMapping("/{id}/follow")
    public R<Void> follow(@PathVariable long id) {
        followService.follow(CurrentUser.id(), id);
        return R.ok();
    }

    @DeleteMapping("/{id}/follow")
    public R<Void> unfollow(@PathVariable long id) {
        followService.unfollow(CurrentUser.id(), id);
        return R.ok();
    }

    @GetMapping("/{id}")
    public R<ProfileVO> profile(@PathVariable long id) {
        return R.ok(userService.profile(id, CurrentUser.idOrNull()));
    }
}
