package com.campustrade.module.want;

import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.common.R;
import com.campustrade.module.want.WantService.CreateReq;
import com.campustrade.module.want.WantService.IdResp;
import com.campustrade.module.want.WantService.ListQuery;
import com.campustrade.module.want.WantService.WantVO;
import com.campustrade.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 求购广场（接口文档 §6） */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WantController {

    private final WantService wantService;

    @GetMapping("/wants")
    public R<PageResult<WantVO>> list(@Valid ListQuery q) {
        return R.ok(wantService.list(q));
    }

    @GetMapping("/wants/{id}")
    public R<WantVO> detail(@PathVariable long id) {
        return R.ok(wantService.detail(id, CurrentUser.idOrNull()));
    }

    @PostMapping("/wants")
    public R<IdResp> create(@Valid @RequestBody CreateReq req) {
        return R.ok(wantService.create(CurrentUser.id(), req));
    }

    @PostMapping("/wants/{id}/close")
    public R<Void> close(@PathVariable long id) {
        wantService.close(CurrentUser.id(), id);
        return R.ok();
    }

    @DeleteMapping("/wants/{id}")
    public R<Void> delete(@PathVariable long id) {
        wantService.delete(CurrentUser.id(), id);
        return R.ok();
    }

    @GetMapping("/users/me/wants")
    public R<PageResult<WantVO>> mine(@RequestParam(required = false) Integer status, PageQuery pq) {
        return R.ok(wantService.mine(CurrentUser.id(), status, pq));
    }
}
