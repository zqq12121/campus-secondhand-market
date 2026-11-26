package com.campustrade.module.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Biz;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.common.R;
import com.campustrade.config.AppProperties;
import com.campustrade.infra.FileStorageService;
import com.campustrade.module.common.CommonService.CampusVO;
import com.campustrade.module.common.CommonService.CategoryNode;
import com.campustrade.module.common.entity.Announcement;
import com.campustrade.module.common.entity.School;
import com.campustrade.module.common.mapper.AnnouncementMapper;
import com.campustrade.module.common.mapper.SchoolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CommonController {

    private static final int TYPE_NOTICE = 1;
    private static final int TYPE_BANNER = 2;

    private final CommonService commonService;
    private final FileStorageService fileStorageService;
    private final AnnouncementMapper announcementMapper;
    private final SchoolMapper schoolMapper;
    private final AppProperties props;

    public record BannerVO(Long id, String title, String imageUrl, String linkUrl) {
    }

    public record NoticeVO(Long id, String title, LocalDateTime createdAt) {
    }

    public record NoticeDetailVO(Long id, String title, String content, LocalDateTime createdAt) {
    }

    public record RootCategoryVO(Long id, String name, String iconUrl) {
    }

    /** 站点配置：学校名与邮箱后缀取 school 表第一条；guaranteeEnabled=false 时前端隐藏担保交易 */
    public record ConfigVO(String schoolName, String emailSuffix, boolean guaranteeEnabled) {
    }

    public record HomeVO(List<BannerVO> banners, List<NoticeVO> notices, List<RootCategoryVO> categories) {
    }

    @GetMapping("/common/campuses")
    public R<List<CampusVO>> campuses() {
        return R.ok(commonService.campuses());
    }

    @GetMapping("/common/config")
    public R<ConfigVO> config() {
        School school = schoolMapper.selectOne(new LambdaQueryWrapper<School>().orderByAsc(School::getId).last("LIMIT 1"));
        return R.ok(new ConfigVO(school == null ? "" : school.getName(), school == null ? "" : school.getEmailSuffix(),
                props.pay().guaranteeEnabled()));
    }

    @GetMapping("/common/categories")
    public R<List<CategoryNode>> categories() {
        return R.ok(commonService.categoryTree(true));
    }

    @PostMapping("/files")
    public R<Map<String, String>> upload(@RequestParam("file") MultipartFile file, @RequestParam("biz") String biz) {
        return R.ok(Map.of("url", fileStorageService.store(file, biz)));
    }

    @GetMapping("/home")
    public R<HomeVO> home() {
        List<BannerVO> banners = online(TYPE_BANNER, 10).stream()
                .map(a -> new BannerVO(a.getId(), a.getTitle(), a.getImageUrl(), a.getLinkUrl())).toList();
        List<NoticeVO> notices = online(TYPE_NOTICE, 5).stream()
                .map(a -> new NoticeVO(a.getId(), a.getTitle(), a.getCreatedAt())).toList();
        List<RootCategoryVO> roots = commonService.categoryTree(true).stream()
                .map(c -> new RootCategoryVO(c.id(), c.name(), c.iconUrl())).toList();
        return R.ok(new HomeVO(banners, notices, roots));
    }

    @GetMapping("/announcements")
    public R<PageResult<NoticeVO>> announcements(PageQuery pq) {
        Page<Announcement> page = announcementMapper.selectPage(pq.toPage(), onlineQuery(TYPE_NOTICE));
        return R.ok(PageResult.of(page, a -> new NoticeVO(a.getId(), a.getTitle(), a.getCreatedAt())));
    }

    @GetMapping("/announcements/{id}")
    public R<NoticeDetailVO> announcement(@PathVariable long id) {
        Announcement a = announcementMapper.selectById(id);
        Biz.check(a != null && a.getType() == TYPE_NOTICE && a.getStatus() == 1, ErrorCode.NOT_FOUND);
        return R.ok(new NoticeDetailVO(a.getId(), a.getTitle(), a.getContent(), a.getCreatedAt()));
    }

    private List<Announcement> online(int type, int limit) {
        return announcementMapper.selectPage(Page.of(1, limit, false), onlineQuery(type)).getRecords();
    }

    private static LambdaQueryWrapper<Announcement> onlineQuery(int type) {
        return new LambdaQueryWrapper<Announcement>().eq(Announcement::getType, type).eq(Announcement::getStatus, 1)
                .orderByAsc(Announcement::getSort).orderByDesc(Announcement::getId);
    }
}
