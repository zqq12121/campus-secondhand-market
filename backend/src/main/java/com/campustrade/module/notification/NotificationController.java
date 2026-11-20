package com.campustrade.module.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.common.R;
import com.campustrade.entity.Notification;
import com.campustrade.mapper.NotificationMapper;
import com.campustrade.module.chat.ChatService;
import com.campustrade.module.notification.NotificationService.NotificationVO;
import com.campustrade.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationMapper mapper;
    private final ChatService chatService;

    public record UnreadVO(int notification, int chat, Map<String, Integer> byType) {
    }

    public record ReadAllReq(Integer type) {
    }

    @GetMapping
    public R<PageResult<NotificationVO>> list(@RequestParam(required = false) Integer type,
                                              @RequestParam(required = false) Boolean isRead, PageQuery pq) {
        Page<Notification> page = mapper.selectPage(pq.toPage(), new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, CurrentUser.id()).eq(type != null, Notification::getType, type)
                .eq(isRead != null, Notification::getIsRead, Boolean.TRUE.equals(isRead) ? 1 : 0)
                .orderByDesc(Notification::getId));
        return R.ok(PageResult.of(page, NotificationService::toVO));
    }

    @GetMapping("/unread-count")
    public R<UnreadVO> unreadCount() {
        Map<String, Integer> byType = new HashMap<>();
        mapper.selectMaps(new QueryWrapper<Notification>().select("type", "COUNT(*) AS cnt")
                        .eq("user_id", CurrentUser.id()).eq("is_read", 0).groupBy("type"))
                .forEach(m -> byType.put(String.valueOf(m.get("type")), ((Number) m.get("cnt")).intValue()));
        int total = byType.values().stream().mapToInt(Integer::intValue).sum();
        return R.ok(new UnreadVO(total, chatService.totalUnread(CurrentUser.id()), byType));
    }

    @PostMapping("/{id}/read")
    public R<Void> read(@PathVariable long id) {
        mapper.update(new LambdaUpdateWrapper<Notification>().eq(Notification::getId, id)
                .eq(Notification::getUserId, CurrentUser.id()).set(Notification::getIsRead, 1));
        return R.ok();
    }

    @PostMapping("/read-all")
    public R<Map<String, Integer>> readAll(@RequestBody(required = false) ReadAllReq req) {
        Integer type = req == null ? null : req.type();
        int n = mapper.update(new LambdaUpdateWrapper<Notification>().eq(Notification::getUserId, CurrentUser.id())
                .eq(Notification::getIsRead, 0).eq(type != null, Notification::getType, type)
                .set(Notification::getIsRead, 1));
        return R.ok(Map.of("updated", n));
    }
}
