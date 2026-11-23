package com.campustrade.module.admin.category;

import com.campustrade.common.R;
import com.campustrade.module.admin.OpLog;
import com.campustrade.module.admin.category.AdminCategoryService.AdminCategory;
import com.campustrade.module.admin.category.AdminCategoryService.CreateReq;
import com.campustrade.module.admin.category.AdminCategoryService.EnabledReq;
import com.campustrade.module.admin.category.AdminCategoryService.IdResp;
import com.campustrade.module.admin.category.AdminCategoryService.SortItem;
import com.campustrade.module.admin.category.AdminCategoryService.UpdateReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 分类管理（接口文档 13.9） */
@Validated
@RestController
@RequestMapping("/api/admin/v1/categories")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('category:manage')")
public class AdminCategoryController {

    private final AdminCategoryService service;

    @GetMapping
    public R<List<AdminCategory>> tree() {
        return R.ok(service.tree());
    }

    @PostMapping
    @OpLog(module = "分类管理", action = "新增")
    public R<IdResp> create(@Valid @RequestBody CreateReq req) {
        return R.ok(service.create(req));
    }

    /** 放在 /{id} 之前声明，避免 sort 被当作 id */
    @PutMapping("/sort")
    @OpLog(module = "分类管理", action = "批量排序")
    public R<Void> sort(@RequestBody @NotEmpty @Size(max = 200) List<@Valid SortItem> items) {
        service.sort(items);
        return R.ok();
    }

    @PutMapping("/{id}")
    @OpLog(module = "分类管理", action = "修改")
    public R<Void> update(@PathVariable long id, @Valid @RequestBody UpdateReq req) {
        service.update(id, req);
        return R.ok();
    }

    @PutMapping("/{id}/enabled")
    @OpLog(module = "分类管理", action = "启停")
    public R<Void> setEnabled(@PathVariable long id, @Valid @RequestBody EnabledReq req) {
        service.setEnabled(id, req.enabled());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @OpLog(module = "分类管理", action = "删除")
    public R<Void> delete(@PathVariable long id) {
        service.delete(id);
        return R.ok();
    }
}
