package com.campustrade.module.goods;

import com.campustrade.common.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class GoodsDtos {

    private GoodsDtos() {
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class SearchQuery extends PageQuery {
        @Size(max = 30)
        private String keyword;
        private Long categoryId;
        private Long campusId;
        private BigDecimal priceMin;
        private BigDecimal priceMax;
        private List<Integer> conditionLevel;
        private Integer tradeMode;
        private Boolean free;
        private String sort;
    }

    public record SaveReq(@NotBlank @Size(max = 30, message = "标题不超过 30 字") String title,
                          @NotBlank @Size(max = 1000, message = "描述不超过 1000 字") String description,
                          @NotEmpty @Size(max = 9, message = "图片 1–9 张") List<String> images,
                          @NotNull Long categoryId,
                          @NotNull BigDecimal price,
                          BigDecimal originalPrice,
                          @NotNull @Min(1) @Max(4) Integer conditionLevel,
                          @NotNull @Min(1) @Max(3) Integer tradeMode,
                          Long campusId,
                          @Size(max = 64) String location,
                          @Min(1) @Max(99) Integer stock,
                          Boolean draft) {
    }

    public record SaveResp(Long id, Integer status, List<String> hitWords) {
    }

    public record SellerBrief(Long id, String nickname, String avatarUrl, String creditLevel) {
    }

    public record GoodsCard(Long id, String title, String coverUrl, BigDecimal price, BigDecimal originalPrice,
                            Integer conditionLevel, Integer tradeMode, Long campusId, String campusName,
                            Integer favoriteCount, SellerBrief seller, LocalDateTime publishedAt) {
    }

    public record MyGoodsCard(Long id, String title, String coverUrl, BigDecimal price, BigDecimal originalPrice,
                              Integer conditionLevel, Integer tradeMode, Long campusId, String campusName,
                              Integer favoriteCount, Integer status, String auditRemark, Integer viewCount,
                              Integer stock, LocalDateTime publishedAt, LocalDateTime updatedAt) {
    }

    public record FavoriteCard(Long id, String title, String coverUrl, BigDecimal price, BigDecimal originalPrice,
                               Integer conditionLevel, Integer tradeMode, Long campusId, String campusName,
                               Integer favoriteCount, SellerBrief seller, LocalDateTime publishedAt, Integer status,
                               LocalDateTime favoritedAt) {
    }

    public record MyGoodsPage(List<MyGoodsCard> list, int total, int page, int size, Map<String, Integer> counts) {
    }

    public record SellerCard(Long id, String nickname, String avatarUrl, Integer creditScore, String creditLevel,
                             boolean badge, String goodRate, int onSaleCount) {
    }

    public record DetailVO(Long id, String title, String description, List<String> images, Long categoryId,
                           List<String> categoryPath, BigDecimal price, BigDecimal originalPrice,
                           Integer conditionLevel, Integer tradeMode, Long campusId, String campusName,
                           String location, Integer stock, Integer status, String auditRemark, Integer viewCount,
                           Integer favoriteCount, LocalDateTime publishedAt, SellerCard seller, boolean favorited,
                           boolean isMine, boolean canBuy, String cannotBuyReason) {
    }

    public record FavoriteResp(boolean favorited, int favoriteCount) {
    }
}
