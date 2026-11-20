package com.campustrade.module.admin;

import com.campustrade.common.PageResult;
import com.campustrade.common.R;
import com.campustrade.module.admin.AdminContentService.AddWordsResp;
import com.campustrade.module.admin.AdminContentService.AnnouncementQuery;
import com.campustrade.module.admin.AdminContentService.AnnouncementReq;
import com.campustrade.module.admin.AdminContentService.AnnouncementVO;
import com.campustrade.module.admin.AdminContentService.IdResp;
import com.campustrade.module.admin.AdminContentService.StatusReq;
import com.campustrade.module.admin.AdminContentService.WordQuery;
import com.campustrade.module.admin.AdminContentService.WordVO;
import com.campustrade.module.admin.AdminContentService.WordsReq;
import com.campustrade.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内容运营（接口文档 13.10） */
@RestController
@RequestMapping("/api/admin/v1")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('content:manage')")
public class AdminContentController {

    private final AdminContentService service;

    @GetMapping("/announcements")
    public R<PageResult<AnnouncementVO>> announcements(AnnouncementQuery q) {
        return R.ok(service.announcements(q));
    }

    @PostMapping("/announcements")
    @OpLog(module = "内容运营", action = "新增公告")
    public R<IdResp> createAnnouncement(@Valid @RequestBody AnnouncementReq req) {
        return R.ok(service.createAnnouncement(CurrentUser.id(), req));
    }

    @PutMapping("/announcements/{id}")
    @OpLog(module = "内容运营", action = "修改公告")
    public R<Void> updateAnnouncement(@PathVariable long id, @Valid @RequestBody AnnouncementReq req) {
        service.updateAnnouncement(id, req);
        return R.ok();
    }

    @PutMapping("/announcements/{id}/status")
    @OpLog(module = "内容运营", action = "公告上下线")
    public R<Void> setAnnouncementStatus(@PathVariable long id, @Valid @RequestBody StatusReq req) {
        service.setAnnouncementStatus(id, req.status());
        return R.ok();
    }

    @DeleteMapping("/announcements/{id}")
    @OpLog(module = "内容运营", action = "删除公告")
    public R<Void> deleteAnnouncement(@PathVariable long id) {
        service.deleteAnnouncement(id);
        return R.ok();
    }

    @GetMapping("/sensitive-words")
    public R<PageResult<WordVO>> words(WordQuery q) {
        return R.ok(service.words(q));
    }

    @PostMapping("/sensitive-words")
    @OpLog(module = "内容运营", action = "新增敏感词")
    public R<AddWordsResp> addWords(@Valid @RequestBody WordsReq req) {
        return R.ok(service.addWords(req.words()));
    }

    @DeleteMapping("/sensitive-words/{id}")
    @OpLog(module = "内容运营", action = "删除敏感词")
    public R<Void> deleteWord(@PathVariable long id) {
        service.deleteWord(id);
        return R.ok();
    }
}
