package com.campustrade.module.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Biz;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.common.TxUtil;
import com.campustrade.entity.Announcement;
import com.campustrade.entity.SensitiveWord;
import com.campustrade.infra.SensitiveWordSync;
import com.campustrade.mapper.AnnouncementMapper;
import com.campustrade.mapper.NotificationMapper;
import com.campustrade.mapper.SensitiveWordMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** 内容运营（接口文档 13.10）：公告 / 轮播图、敏感词 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminContentService {

    private static final int TYPE_NOTICE = 1, TYPE_BANNER = 2;
    private static final int NOTIFY_CONTENT_MAX = 500;

    private final AnnouncementMapper announcementMapper;
    private final NotificationMapper notificationMapper;
    private final SensitiveWordMapper wordMapper;
    private final SensitiveWordSync wordSync;

    // ---------------------------------------------------------------- DTO

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class AnnouncementQuery extends PageQuery {
        private Integer type;
        private Integer status;
    }

    public record AnnouncementReq(@NotNull @Min(1) @Max(2) Integer type,
                                  @NotBlank @Size(max = 64, message = "标题不超过 64 字") String title,
                                  @Size(max = 20000) String content,
                                  @Size(max = 255) String imageUrl,
                                  @Size(max = 255) String linkUrl,
                                  Integer sort,
                                  @Min(0) @Max(1) Integer status,
                                  Boolean notifyUsers) {
    }

    public record StatusReq(@NotNull @Min(0) @Max(1) Integer status) {
    }

    public record AnnouncementVO(Long id, Integer type, String title, String content, String imageUrl, String linkUrl,
                                 Integer sort, Integer status, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record IdResp(Long id) {
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class WordQuery extends PageQuery {
        private String keyword;
    }

    public record WordsReq(@NotEmpty @Size(max = 500, message = "一次最多 500 个") List<String> words) {
    }

    public record WordVO(Long id, String word, LocalDateTime createdAt) {
    }

    public record AddWordsResp(int added, int skipped) {
    }

    // ---------------------------------------------------------------- 公告与轮播

    public PageResult<AnnouncementVO> announcements(AnnouncementQuery q) {
        Page<Announcement> page = announcementMapper.selectPage(q.toPage(), new LambdaQueryWrapper<Announcement>()
                .eq(q.getType() != null, Announcement::getType, q.getType())
                .eq(q.getStatus() != null, Announcement::getStatus, q.getStatus())
                .orderByAsc(Announcement::getType).orderByAsc(Announcement::getSort).orderByDesc(Announcement::getId));
        return PageResult.of(page, AdminContentService::toVO);
    }

    /** type=1 且上线且 notifyUsers=true 时，提交后异步给全体用户写系统通知 */
    @Transactional
    public IdResp createAnnouncement(long adminId, AnnouncementReq req) {
        Announcement a = new Announcement();
        fill(a, req);
        a.setStatus(req.status() == null ? 1 : req.status());
        a.setCreatedBy(adminId);
        announcementMapper.insert(a);
        if (Boolean.TRUE.equals(req.notifyUsers()) && a.getType() == TYPE_NOTICE && a.getStatus() == 1) {
            String title = a.getTitle();
            String content = notifyContent(a);
            TxUtil.afterCommit(() -> CompletableFuture.runAsync(() -> {
                int n = notificationMapper.insertForAllUsers(NotifyType.SYSTEM, title, content);
                log.info("公告 {} 已通知 {} 位用户", a.getId(), n);
            }).exceptionally(e -> {
                log.error("公告 {} 群发通知失败", a.getId(), e);
                return null;
            }));
        }
        return new IdResp(a.getId());
    }

    public void updateAnnouncement(long id, AnnouncementReq req) {
        Announcement a = Biz.notNull(announcementMapper.selectById(id), ErrorCode.NOT_FOUND);
        fill(a, req);
        if (req.status() != null) {
            a.setStatus(req.status());
        }
        // 置空后 updateById 不写这两列，updated_at 由数据库 ON UPDATE 刷新
        a.setCreatedAt(null);
        a.setUpdatedAt(null);
        announcementMapper.updateById(a);
    }

    public void setAnnouncementStatus(long id, int status) {
        Biz.notNull(announcementMapper.selectById(id), ErrorCode.NOT_FOUND);
        announcementMapper.update(new LambdaUpdateWrapper<Announcement>().eq(Announcement::getId, id)
                .set(Announcement::getStatus, status));
    }

    public void deleteAnnouncement(long id) {
        Biz.check(announcementMapper.deleteById(id) == 1, ErrorCode.NOT_FOUND);
    }

    // ---------------------------------------------------------------- 敏感词

    public PageResult<WordVO> words(WordQuery q) {
        String kw = q.getKeyword() == null ? null : q.getKeyword().trim();
        Page<SensitiveWord> page = wordMapper.selectPage(q.toPage(), new LambdaQueryWrapper<SensitiveWord>()
                .like(kw != null && !kw.isEmpty(), SensitiveWord::getWord, kw).orderByDesc(SensitiveWord::getId));
        return PageResult.of(page, w -> new WordVO(w.getId(), w.getWord(), w.getCreatedAt()));
    }

    /** 批量新增，空白去掉、重复与已存在的计入 skipped */
    public AddWordsResp addWords(List<String> words) {
        Set<String> uniq = new LinkedHashSet<>();
        for (String w : words) {
            String t = w == null ? "" : w.trim();
            if (!t.isEmpty()) {
                Biz.check(t.length() <= 32, ErrorCode.BAD_REQUEST, "敏感词不超过 32 字：" + t);
                uniq.add(t);
            }
        }
        Biz.check(!uniq.isEmpty(), ErrorCode.BAD_REQUEST, "请至少填写一个敏感词");
        int added = 0;
        for (String w : uniq) {
            SensitiveWord sw = new SensitiveWord();
            sw.setWord(w);
            try {
                wordMapper.insert(sw);
                added++;
            } catch (DuplicateKeyException ignored) {
                // 已存在，跳过
            }
        }
        if (added > 0) {
            wordSync.publishReload();
        }
        return new AddWordsResp(added, words.size() - added);
    }

    public void deleteWord(long id) {
        Biz.check(wordMapper.deleteById(id) == 1, ErrorCode.NOT_FOUND);
        wordSync.publishReload();
    }

    // ---------------------------------------------------------------- 内部

    private static void fill(Announcement a, AnnouncementReq req) {
        String imageUrl = blankToNull(req.imageUrl());
        Biz.check(req.type() != TYPE_BANNER || imageUrl != null, ErrorCode.BAD_REQUEST, "轮播图必须上传图片");
        a.setType(req.type());
        a.setTitle(req.title().trim());
        a.setContent(blankToNull(req.content()));
        a.setImageUrl(imageUrl);
        a.setLinkUrl(blankToNull(req.linkUrl()));
        a.setSort(req.sort() == null ? 0 : req.sort());
    }

    private static String notifyContent(Announcement a) {
        String c = a.getContent() == null ? a.getTitle() : a.getContent();
        return c.length() <= NOTIFY_CONTENT_MAX ? c : c.substring(0, NOTIFY_CONTENT_MAX - 1) + "…";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static AnnouncementVO toVO(Announcement a) {
        return new AnnouncementVO(a.getId(), a.getType(), a.getTitle(), a.getContent(), a.getImageUrl(),
                a.getLinkUrl(), a.getSort(), a.getStatus(), a.getCreatedAt(), a.getUpdatedAt());
    }
}
