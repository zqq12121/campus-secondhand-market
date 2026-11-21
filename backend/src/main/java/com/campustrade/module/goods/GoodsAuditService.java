package com.campustrade.module.goods;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.common.Consts.AuditResult;
import com.campustrade.common.Consts.GoodsStatus;
import com.campustrade.entity.AuditRecord;
import com.campustrade.entity.Goods;
import com.campustrade.entity.User;
import com.campustrade.infra.SensitiveWordService;
import com.campustrade.mapper.AuditRecordMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.module.want.WantMatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 发布审核策略：敏感词未命中且信用 ≥ 80 直接上架，否则进人工审核（PRD 4.2） */
@Service
@RequiredArgsConstructor
public class GoodsAuditService {

    static final int TARGET_GOODS = 1;
    static final int AUDITOR_SYSTEM = 1;
    static final int AUDITOR_ADMIN = 2;

    private final SensitiveWordService sensitiveWordService;
    private final AuditRecordMapper auditRecordMapper;
    private final GoodsMapper goodsMapper;
    private final WantMatchService wantMatchService;

    /** 写入状态（待审核 / 在售）并返回命中的敏感词 */
    public List<String> submit(Goods goods, User seller) {
        List<String> hits = sensitiveWordService.findHits(goods.getTitle() + "\n" + goods.getDescription());
        boolean pass = hits.isEmpty() && seller.getCreditScore() >= 80;
        LambdaUpdateWrapper<Goods> w = new LambdaUpdateWrapper<Goods>().eq(Goods::getId, goods.getId())
                .set(Goods::getStatus, pass ? GoodsStatus.ON_SALE : GoodsStatus.PENDING)
                .set(Goods::getAuditRemark, null);
        if (pass) {
            w.set(Goods::getPublishedAt, LocalDateTime.now());
        }
        goodsMapper.update(w);
        goods.setStatus(pass ? GoodsStatus.ON_SALE : GoodsStatus.PENDING);
        if (pass) {
            wantMatchService.onGoodsOnSale(goods);
        }
        String reason = pass ? "自动审核通过" : (hits.isEmpty() ? "信用分低于 80，转人工审核" : "命中敏感词，转人工审核");
        record(goods.getId(), AUDITOR_SYSTEM, null, pass ? AuditResult.PASS : AuditResult.MANUAL, reason,
                hits.isEmpty() ? null : String.join(",", hits));
        return hits;
    }

    public void record(long goodsId, int auditorType, Long auditorId, int result, String reason, String hitWords) {
        AuditRecord r = new AuditRecord();
        r.setTargetType(TARGET_GOODS);
        r.setTargetId(goodsId);
        r.setAuditorType(auditorType);
        r.setAuditorId(auditorId);
        r.setResult(result);
        r.setReason(reason);
        r.setHitWords(hitWords);
        auditRecordMapper.insert(r);
    }
}
