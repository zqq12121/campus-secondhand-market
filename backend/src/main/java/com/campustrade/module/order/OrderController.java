package com.campustrade.module.order;

import com.campustrade.common.PageQuery;
import com.campustrade.common.R;
import com.campustrade.module.order.OrderDtos.CancelReq;
import com.campustrade.module.order.OrderDtos.CreateReq;
import com.campustrade.module.order.OrderDtos.CreateResp;
import com.campustrade.module.order.OrderDtos.DetailVO;
import com.campustrade.module.order.OrderDtos.OrderPage;
import com.campustrade.module.order.OrderDtos.VerifyMeetCodeReq;
import com.campustrade.module.pay.PaymentService;
import com.campustrade.module.pay.PaymentService.PayResult;
import com.campustrade.module.pay.PaymentService.PayStatus;
import com.campustrade.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final PaymentService paymentService;

    @PostMapping
    public R<CreateResp> create(@Valid @RequestBody CreateReq req) {
        return R.ok(orderService.create(CurrentUser.id(), req));
    }

    @GetMapping
    public R<OrderPage> list(@RequestParam String role, @RequestParam(required = false) List<Integer> status,
                             PageQuery pq) {
        return R.ok(orderService.list(CurrentUser.id(), role, status, pq));
    }

    @GetMapping("/{id}")
    public R<DetailVO> detail(@PathVariable long id) {
        return R.ok(orderService.detail(CurrentUser.id(), id));
    }

    @PostMapping("/{id}/seller-confirm")
    public R<Void> sellerConfirm(@PathVariable long id) {
        orderService.sellerConfirm(CurrentUser.id(), id);
        return R.ok();
    }

    @PostMapping("/{id}/verify-meet-code")
    public R<Void> verifyMeetCode(@PathVariable long id, @Valid @RequestBody VerifyMeetCodeReq req) {
        orderService.verifyMeetCode(CurrentUser.id(), id, req.code());
        return R.ok();
    }

    @PostMapping("/{id}/complete")
    public R<Void> complete(@PathVariable long id) {
        orderService.complete(CurrentUser.id(), id);
        return R.ok();
    }

    @PostMapping("/{id}/deliver")
    public R<Void> deliver(@PathVariable long id) {
        orderService.deliver(CurrentUser.id(), id);
        return R.ok();
    }

    @PostMapping("/{id}/confirm-receipt")
    public R<Void> confirmReceipt(@PathVariable long id) {
        orderService.confirmReceipt(CurrentUser.id(), id);
        return R.ok();
    }

    @PostMapping("/{id}/pay")
    public R<PayResult> pay(@PathVariable long id) {
        return R.ok(paymentService.startPay(CurrentUser.id(), id));
    }

    @GetMapping("/{id}/pay-status")
    public R<PayStatus> payStatus(@PathVariable long id) {
        return R.ok(paymentService.payStatus(CurrentUser.id(), id));
    }

    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable long id, @Valid @RequestBody CancelReq req) {
        orderService.cancel(CurrentUser.id(), id, req.reason());
        return R.ok();
    }
}
