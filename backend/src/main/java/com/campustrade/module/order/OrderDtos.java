package com.campustrade.module.order;

import com.campustrade.module.user.UserDtos.UserBrief;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateReq(@NotNull Long goodsId,
                            @NotNull @Min(1) @Max(99) Integer quantity,
                            @NotNull @Min(1) @Max(2) Integer tradeMode,
                            @NotBlank @Size(max = 64) String requestId) {
    }

    public record CreateResp(Long id, String orderNo, Integer status, LocalDateTime payExpireAt) {
    }

    public record CancelReq(@NotBlank @Size(max = 200) String reason) {
    }

    public record OrderCard(Long id, String orderNo, Long goodsId, String goodsTitle, String goodsCover,
                            BigDecimal unitPrice, Integer quantity, BigDecimal totalAmount, Integer tradeMode,
                            Integer status, UserBrief counterpart, List<String> actions, boolean reviewed,
                            LocalDateTime createdAt) {
    }

    public record OrderPage(List<OrderCard> list, int total, int page, int size, Map<String, Integer> counts) {
    }

    public record GoodsSnapshot(Long id, String title, String cover, BigDecimal unitPrice, String location,
                                String campusName) {
    }

    public record Timeline(LocalDateTime createdAt, LocalDateTime sellerConfirmedAt, LocalDateTime paidAt,
                           LocalDateTime deliveredAt, LocalDateTime finishedAt, LocalDateTime canceledAt) {
    }

    public record Deadline(String action, LocalDateTime at) {
    }

    public record LogVO(Integer fromStatus, Integer toStatus, String action, Integer operatorType, String remark,
                        LocalDateTime createdAt) {
    }

    public record ReviewBlock(LocalDateTime deadline, Object mine, Object theirs) {
    }

    public record DetailVO(Long id, String orderNo, String myRole, GoodsSnapshot goods, Integer quantity,
                           BigDecimal totalAmount, Integer tradeMode, Integer status, UserBrief buyer,
                           UserBrief seller, Long conversationId, Timeline timeline, Deadline deadline,
                           String cancelReason, Integer cancelBy, Object payment, Object afterSale,
                           ReviewBlock review, List<String> actions, List<LogVO> logs) {
    }
}
