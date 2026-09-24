package com.wine.controller;

import com.wine.common.Result;
import com.wine.dto.OrderResp;
import com.wine.service.PaymentService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 支付接口
 */
@RestController
@RequestMapping("/payment")
public class PaymentController {

    @Resource
    private PaymentService paymentService;

    /** 发起支付，返回通联收银台URL */
    @PostMapping("/pay/{orderNo}")
    public Result<OrderResp> pay(@PathVariable String orderNo) {
        return Result.success(paymentService.pay(orderNo));
    }

    /** 通联异步通知回调 */
    @PostMapping("/notify")
    public String notify(@RequestParam Map<String, String> params) {
        return paymentService.handleNotify(params);
    }

    /** 退款 */
    @PostMapping("/refund/{orderNo}")
    public Result<Void> refund(@PathVariable String orderNo) {
        paymentService.refund(orderNo);
        return Result.success();
    }
}
