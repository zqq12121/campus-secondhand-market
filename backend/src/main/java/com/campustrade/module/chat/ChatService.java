package com.campustrade.module.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Biz;
import com.campustrade.common.BizException;
import com.campustrade.common.Consts.UserStatus;
import com.campustrade.common.Consts.WantStatus;
import com.campustrade.common.CreditLevel;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.entity.Conversation;
import com.campustrade.entity.Goods;
import com.campustrade.entity.Message;
import com.campustrade.entity.User;
import com.campustrade.entity.WantPost;
import com.campustrade.infra.FileStorageService;
import com.campustrade.infra.OnlineTracker;
import com.campustrade.infra.RateLimiter;
import com.campustrade.infra.RealtimePusher;
import com.campustrade.infra.SensitiveWordService;
import com.campustrade.mapper.ConversationMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.MessageMapper;
import com.campustrade.mapper.WantPostMapper;
import com.campustrade.module.user.UserService;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 一对一聊天（接口文档第 7 章）：同一对用户只有一个会话 */
@Service
@RequiredArgsConstructor
public class ChatService {

    public static final int TYPE_TEXT = 1;
    public static final int TYPE_IMAGE = 2;
    public static final int TYPE_GOODS = 3;
    public static final int TYPE_SYSTEM = 4;
    private static final int TEXT_MAX = 500;
    private static final int PAGE_MAX = 50;
    private static final String RISK_TIP = "站外交易无保障，建议使用平台担保交易";
    /** 微信号 / QQ 号 / 银行卡号 */
    private static final List<Pattern> RISK_PATTERNS = List.of(
            Pattern.compile("(?i)(微信|weixin|wechat|vx|wx|v信)\\s*[:：号]?\\s*[a-z][-_a-z0-9]{5,19}"),
            Pattern.compile("(?i)(qq|扣扣)\\s*[:：号]?\\s*[1-9]\\d{4,10}"),
            Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)"));

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final GoodsMapper goodsMapper;
    private final WantPostMapper wantMapper;
    private final UserService userService;
    private final SensitiveWordService sensitiveWordService;
    private final FileStorageService fileStorageService;
    private final RateLimiter rateLimiter;
    private final OnlineTracker onlineTracker;
    private final RealtimePusher pusher;

    // ---------------------------------------------------------------- DTO

    public record StartReq(@NotNull Long peerUserId, Long goodsId, Long wantId) {
    }

    public record SendReq(@NotNull @Min(1) @Max(3) Integer type, @NotBlank @Size(max = 1000) String content,
                          @Size(max = 64) String clientMsgId) {
    }

    public record WsSendReq(Long conversationId, Integer type, String content, String clientMsgId) {
    }

    public record PeerVO(Long id, String nickname, String avatarUrl, String creditLevel, boolean online) {
    }

    public record GoodsCardVO(Long id, String title, String coverUrl, BigDecimal price, Integer status,
                              Integer tradeMode) {
    }

    public record ConversationItem(Long id, PeerVO peer, GoodsCardVO goods, String lastMessage,
                                   LocalDateTime lastMessageAt, int unread) {
    }

    public record ConversationDetail(Long id, PeerVO peer, GoodsCardVO goods, boolean blockedByMe,
                                     boolean blockedByPeer) {
    }

    public record MessageVO(Long id, Long senderId, Integer type, String content, GoodsCardVO goods,
                            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS") LocalDateTime createdAt) {
    }

    public record MessagePage(List<MessageVO> list, boolean hasMore) {
    }

    public record SendResult(MessageVO message, String clientMsgId, String riskTip) {
    }

    public record MessageEvent(String event, Long conversationId, String clientMsgId, MessageVO message,
                               String riskTip, int conversationUnread, int totalUnread) {
    }

    // ---------------------------------------------------------------- 会话

    @Transactional
    public ConversationDetail start(long me, StartReq req) {
        Biz.check(me != req.peerUserId(), ErrorCode.BAD_REQUEST, "不能和自己聊天");
        userService.require(req.peerUserId());
        if (req.goodsId() != null) {
            Biz.notNull(goodsMapper.selectById(req.goodsId()), ErrorCode.GOODS_NOT_FOUND);
        }
        if (req.wantId() != null) {
            // 「我有」：只能找求购帖作者，且帖子仍在进行中（会话不记录 wantId）
            WantPost w = wantMapper.selectById(req.wantId());
            Biz.check(w != null && w.getStatus() == WantStatus.ACTIVE && w.getExpireAt().isAfter(LocalDateTime.now()),
                    ErrorCode.WANT_NOT_FOUND);
            Biz.check(w.getUserId().equals(req.peerUserId()), ErrorCode.BAD_REQUEST, "只能联系求购发布者");
        }
        Conversation c = getOrCreate(me, req.peerUserId(), req.goodsId());
        return detail(me, c.getId());
    }

    public PageResult<ConversationItem> list(long me, PageQuery pq) {
        Page<Conversation> page = conversationMapper.selectPage(pq.toPage(), new LambdaQueryWrapper<Conversation>()
                .and(w -> w.eq(Conversation::getUserSmallId, me).or().eq(Conversation::getUserBigId, me))
                .isNotNull(Conversation::getLastMessageAt)
                .orderByDesc(Conversation::getLastMessageAt));
        List<Conversation> records = page.getRecords();
        Map<Long, User> peers = userService.mapByIds(records.stream().map(c -> peerOf(c, me)).toList());
        Map<Long, GoodsCardVO> goods = goodsCards(records.stream().map(Conversation::getGoodsId).toList());
        return PageResult.of(page, c -> new ConversationItem(c.getId(), peerVO(peers.get(peerOf(c, me))),
                goods.get(c.getGoodsId()), c.getLastMessage(), c.getLastMessageAt(), unreadOf(c, me)));
    }

    public ConversationDetail detail(long me, long conversationId) {
        Conversation c = requireMember(me, conversationId);
        long peer = peerOf(c, me);
        Map<Long, GoodsCardVO> goods = goodsCards(Collections.singletonList(c.getGoodsId()));
        return new ConversationDetail(c.getId(), peerVO(userService.require(peer)), goods.get(c.getGoodsId()),
                userService.isBlocked(me, peer), userService.isBlocked(peer, me));
    }

    /** 历史消息：按 id 倒序取，返回时转为升序 */
    public MessagePage messages(long me, long conversationId, Long beforeId, Integer size) {
        requireMember(me, conversationId);
        return history(conversationId, beforeId, size, null);
    }

    public MessagePage history(long conversationId, Long beforeId, Integer size, LocalDateTime since) {
        int n = size == null || size < 1 ? 30 : Math.min(size, PAGE_MAX);
        List<Message> rows = messageMapper.selectPage(Page.of(1, n + 1, false), new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId).lt(beforeId != null, Message::getId, beforeId)
                .ge(since != null, Message::getCreatedAt, since).orderByDesc(Message::getId)).getRecords();
        boolean hasMore = rows.size() > n;
        List<Message> page = new ArrayList<>(rows.subList(0, Math.min(n, rows.size())));
        Collections.reverse(page);
        return new MessagePage(toVOs(page), hasMore);
    }

    /** 买卖双方的会话（不存在返回 null） */
    public Conversation findPair(long a, long b) {
        return conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserSmallId, Math.min(a, b)).eq(Conversation::getUserBigId, Math.max(a, b)));
    }

    // ---------------------------------------------------------------- 消息

    @Transactional
    public SendResult send(long me, long conversationId, int type, String rawContent, String clientMsgId) {
        Conversation c = requireMember(me, conversationId);
        long peer = peerOf(c, me);
        User sender = userService.require(me);
        Biz.check(sender.getStatus() != UserStatus.MUTED, ErrorCode.MUTED);
        Biz.check(!userService.isBlocked(peer, me), ErrorCode.BLOCKED_BY_PEER);
        rateLimiter.acquire("chat:" + me, 5, 1);

        String content = rawContent.trim();
        String riskTip = null;
        String summary;
        switch (type) {
            case TYPE_TEXT -> {
                Biz.check(!content.isEmpty() && content.length() <= TEXT_MAX, ErrorCode.BAD_REQUEST, "消息不超过 500 字");
                String raw = content;
                riskTip = RISK_PATTERNS.stream().anyMatch(p -> p.matcher(raw).find()) ? RISK_TIP : null;
                content = sensitiveWordService.replace(raw);
                summary = content;
            }
            case TYPE_IMAGE -> {
                Biz.check(fileStorageService.isOwnUrl(content), ErrorCode.BAD_REQUEST, "图片必须通过本平台上传");
                summary = "[图片]";
            }
            case TYPE_GOODS -> {
                Goods g = content.matches("\\d{1,19}") ? goodsMapper.selectById(Long.parseLong(content)) : null;
                Biz.check(g != null && (g.getSellerId() == me || g.getSellerId() == peer), ErrorCode.BAD_REQUEST,
                        "只能发送双方的商品");
                summary = "[商品] " + g.getTitle();
            }
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "消息类型不合法");
        }
        Message m = insert(c, me, type, content, summary, peer == c.getUserSmallId(), peer == c.getUserBigId());
        MessageVO vo = toVOs(List.of(m)).getFirst();
        for (long uid : List.of(me, peer)) {
            Conversation fresh = conversationMapper.selectById(c.getId());
            pusher.push(uid, RealtimePusher.MESSAGES, new MessageEvent("MESSAGE", c.getId(),
                    uid == me ? clientMsgId : null, vo, uid == me ? riskTip : null, unreadOf(fresh, uid),
                    totalUnread(uid)));
        }
        return new SendResult(vo, clientMsgId, riskTip);
    }

    /** 系统消息（如"对方已下单"），双方未读数各 +1 并推送 */
    @Transactional
    public void sendSystem(long userA, long userB, Long goodsId, String text) {
        Conversation c = getOrCreate(userA, userB, goodsId);
        Message m = insert(c, 0L, TYPE_SYSTEM, text, text, true, true);
        MessageVO vo = toVOs(List.of(m)).getFirst();
        Conversation fresh = conversationMapper.selectById(c.getId());
        for (long uid : List.of(userA, userB)) {
            pusher.push(uid, RealtimePusher.MESSAGES, new MessageEvent("MESSAGE", c.getId(), null, vo, null,
                    unreadOf(fresh, uid), totalUnread(uid)));
        }
    }

    public int markRead(long me, long conversationId) {
        Conversation c = requireMember(me, conversationId);
        boolean small = c.getUserSmallId() == me;
        conversationMapper.update(new LambdaUpdateWrapper<Conversation>().eq(Conversation::getId, conversationId)
                .set(small ? Conversation::getSmallUnread : Conversation::getBigUnread, 0));
        return totalUnread(me);
    }

    public int totalUnread(long uid) {
        Object sum = conversationMapper.selectObjs(new QueryWrapper<Conversation>()
                .select("IFNULL(SUM(CASE WHEN user_small_id = " + uid + " THEN small_unread ELSE big_unread END), 0)")
                .and(w -> w.eq("user_small_id", uid).or().eq("user_big_id", uid))).stream().findFirst().orElse(0);
        return ((Number) sum).intValue();
    }

    // ---------------------------------------------------------------- 内部

    private Conversation getOrCreate(long a, long b, Long goodsId) {
        Conversation c = findPair(a, b);
        if (c == null) {
            c = new Conversation();
            c.setUserSmallId(Math.min(a, b));
            c.setUserBigId(Math.max(a, b));
            c.setGoodsId(goodsId);
            try {
                conversationMapper.insert(c);
                return c;
            } catch (DuplicateKeyException e) {
                c = findPair(a, b);
            }
        }
        if (goodsId != null && !goodsId.equals(c.getGoodsId())) {
            conversationMapper.update(new LambdaUpdateWrapper<Conversation>().eq(Conversation::getId, c.getId())
                    .set(Conversation::getGoodsId, goodsId));
            c.setGoodsId(goodsId);
        }
        return c;
    }

    private Message insert(Conversation c, long senderId, int type, String content, String summary,
                           boolean incSmall, boolean incBig) {
        Message m = new Message();
        m.setConversationId(c.getId());
        m.setSenderId(senderId);
        m.setType(type);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(m);
        LambdaUpdateWrapper<Conversation> w = new LambdaUpdateWrapper<Conversation>().eq(Conversation::getId, c.getId())
                .set(Conversation::getLastMessage, summary.length() > 100 ? summary.substring(0, 100) : summary)
                .set(Conversation::getLastMessageAt, m.getCreatedAt());
        if (incSmall) {
            w.setSql("small_unread = small_unread + 1");
        }
        if (incBig) {
            w.setSql("big_unread = big_unread + 1");
        }
        conversationMapper.update(w);
        return m;
    }

    private Conversation requireMember(long me, long conversationId) {
        Conversation c = conversationMapper.selectById(conversationId);
        Biz.check(c != null && (c.getUserSmallId() == me || c.getUserBigId() == me), ErrorCode.NOT_CONVERSATION_MEMBER);
        return c;
    }

    private static long peerOf(Conversation c, long me) {
        return c.getUserSmallId() == me ? c.getUserBigId() : c.getUserSmallId();
    }

    private static int unreadOf(Conversation c, long me) {
        return c.getUserSmallId() == me ? c.getSmallUnread() : c.getBigUnread();
    }

    private PeerVO peerVO(User u) {
        return u == null ? null : new PeerVO(u.getId(), u.getNickname(), u.getAvatarUrl(),
                CreditLevel.of(u.getCreditScore()).name(), onlineTracker.isOnline(u.getId()));
    }

    private Map<Long, GoodsCardVO> goodsCards(List<Long> ids) {
        List<Long> valid = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (valid.isEmpty()) {
            return Map.of();
        }
        return goodsMapper.selectByIds(valid).stream().collect(Collectors.toMap(Goods::getId,
                g -> new GoodsCardVO(g.getId(), g.getTitle(), g.getCoverUrl(), g.getPrice(), g.getStatus(),
                        g.getTradeMode()), (x, y) -> x));
    }

    private List<MessageVO> toVOs(List<Message> messages) {
        Map<Long, GoodsCardVO> goods = goodsCards(messages.stream().filter(m -> m.getType() == TYPE_GOODS)
                .map(m -> Long.parseLong(m.getContent())).toList());
        return messages.stream().map(m -> new MessageVO(m.getId(), m.getSenderId(), m.getType(), m.getContent(),
                m.getType() == TYPE_GOODS ? goods.get(Long.parseLong(m.getContent())) : null, m.getCreatedAt()))
                .toList();
    }
}
