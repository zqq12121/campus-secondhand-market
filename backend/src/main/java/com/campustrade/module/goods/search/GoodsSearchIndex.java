package com.campustrade.module.goods.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.campustrade.common.Consts.GoodsStatus;
import com.campustrade.common.Consts.TradeMode;
import com.campustrade.module.goods.GoodsDtos.SearchQuery;
import com.campustrade.module.goods.entity.Goods;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 商品全文检索（P2，SEARCH_ENGINE=es 时启用）。索引只放在售商品，由 GoodsIndexSync 按 updated_at 增量同步；
 * 检索只返回命中的商品 ID，商品数据仍从 MySQL 读取，索引短暂滞后不会展示出过期信息。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.engine", havingValue = "es")
public class GoodsSearchIndex {

    static final String INDEX = "goods";

    /** title 与 description 用 smartcn 中文分词；数值字段只用于过滤和排序 */
    private static final String MAPPING = """
            {
              "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
              "mappings": {
                "properties": {
                  "id":             { "type": "long" },
                  "title":          { "type": "text", "analyzer": "smartcn" },
                  "description":    { "type": "text", "analyzer": "smartcn" },
                  "categoryId":     { "type": "long" },
                  "campusId":       { "type": "long" },
                  "sellerId":       { "type": "long" },
                  "price":          { "type": "scaled_float", "scaling_factor": 100 },
                  "conditionLevel": { "type": "integer" },
                  "tradeMode":      { "type": "integer" },
                  "publishedAt":    { "type": "date", "format": "epoch_millis" }
                }
              }
            }
            """;

    private final ElasticsearchClient client;

    public record GoodsDoc(Long id, String title, String description, Long categoryId, Long campusId, Long sellerId,
                           BigDecimal price, Integer conditionLevel, Integer tradeMode, Long publishedAt) {
    }

    /** 命中的商品 ID（按排序）与总数 */
    public record Hits(List<Long> ids, int total) {
    }

    /** 索引不存在时创建；返回是否新建（新建后需要全量同步） */
    public boolean ensureIndex() throws IOException {
        if (client.indices().exists(e -> e.index(INDEX)).value()) {
            return false;
        }
        client.indices().create(c -> c.index(INDEX).withJson(new StringReader(MAPPING)));
        return true;
    }

    /** 在售的写入索引，其余状态从索引删除 */
    public void sync(Collection<Goods> goods) throws IOException {
        if (goods.isEmpty()) {
            return;
        }
        BulkRequest.Builder b = new BulkRequest.Builder();
        for (Goods g : goods) {
            String id = String.valueOf(g.getId());
            if (g.getStatus() == GoodsStatus.ON_SALE && !Integer.valueOf(1).equals(g.getDeleted())) {
                GoodsDoc doc = toDoc(g);
                b.operations(op -> op.index(i -> i.index(INDEX).id(id).document(doc)));
            } else {
                b.operations(op -> op.delete(d -> d.index(INDEX).id(id)));
            }
        }
        client.bulk(b.build());
    }

    /**
     * 与 MySQL 版 GoodsService.search 的筛选和排序一致。
     *
     * @param categoryIds 已展开的分类（一级分类展开为其下二级分类），null 表示不限
     * @param blockedBy   拉黑了当前用户的卖家，这些卖家的商品不展示
     */
    public Hits search(SearchQuery q, String keyword, List<Long> categoryIds, List<Long> blockedBy) throws IOException {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        bool.must(m -> m.multiMatch(mm -> mm.query(keyword).fields("title^3", "description")));
        if (categoryIds != null) {
            bool.filter(f -> f.terms(t -> t.field("categoryId").terms(v -> v.value(longs(categoryIds)))));
        }
        if (q.getCampusId() != null) {
            bool.filter(f -> f.term(t -> t.field("campusId").value(q.getCampusId())));
        }
        if (q.getPriceMin() != null || q.getPriceMax() != null || Boolean.TRUE.equals(q.getFree())) {
            bool.filter(f -> f.range(r -> r.number(n -> {
                n.field("price");
                if (Boolean.TRUE.equals(q.getFree())) {
                    return n.gte(0.0).lte(0.0);
                }
                if (q.getPriceMin() != null) {
                    n.gte(q.getPriceMin().doubleValue());
                }
                if (q.getPriceMax() != null) {
                    n.lte(q.getPriceMax().doubleValue());
                }
                return n;
            })));
        }
        if (q.getConditionLevel() != null && !q.getConditionLevel().isEmpty()) {
            bool.filter(f -> f.terms(t -> t.field("conditionLevel").terms(v -> v.value(
                    q.getConditionLevel().stream().map(FieldValue::of).toList()))));
        }
        if (q.getTradeMode() != null) {
            bool.filter(f -> f.terms(t -> t.field("tradeMode").terms(v -> v.value(
                    List.of(FieldValue.of(q.getTradeMode()), FieldValue.of(TradeMode.BOTH))))));
        }
        if (!blockedBy.isEmpty()) {
            bool.mustNot(m -> m.terms(t -> t.field("sellerId").terms(v -> v.value(longs(blockedBy)))));
        }
        String sort = q.getSort() == null ? "DEFAULT" : q.getSort();
        var page = q.toPage();
        SearchResponse<GoodsDoc> resp = client.search(s -> {
            s.index(INDEX).query(qq -> qq.bool(bool.build())).from((int) ((page.getCurrent() - 1) * page.getSize()))
                    .size((int) page.getSize())
                    .trackTotalHits(t -> t.enabled(true)).source(src -> src.fetch(false));
            switch (sort) {
                case "PRICE_ASC" -> s.sort(o -> o.field(fs -> fs.field("price").order(SortOrder.Asc)))
                        .sort(o -> o.field(fs -> fs.field("id").order(SortOrder.Desc)));
                case "PRICE_DESC" -> s.sort(o -> o.field(fs -> fs.field("price").order(SortOrder.Desc)))
                        .sort(o -> o.field(fs -> fs.field("id").order(SortOrder.Desc)));
                // 默认按相关度
                case "DEFAULT" -> s.sort(o -> o.score(sc -> sc.order(SortOrder.Desc)));
                default -> s.sort(o -> o.field(fs -> fs.field("publishedAt").order(SortOrder.Desc)));
            }
            return s;
        }, GoodsDoc.class);
        List<Long> ids = resp.hits().hits().stream().map(Hit::id).filter(Objects::nonNull).map(Long::valueOf).toList();
        long total = resp.hits().total() == null ? ids.size() : resp.hits().total().value();
        return new Hits(ids, (int) total);
    }

    private static List<FieldValue> longs(Collection<Long> ids) {
        return ids.stream().map(FieldValue::of).toList();
    }

    static GoodsDoc toDoc(Goods g) {
        return new GoodsDoc(g.getId(), g.getTitle(), g.getDescription(), g.getCategoryId(), g.getCampusId(),
                g.getSellerId(), g.getPrice(), g.getConditionLevel(), g.getTradeMode(),
                g.getPublishedAt() == null ? null
                        : g.getPublishedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }
}
