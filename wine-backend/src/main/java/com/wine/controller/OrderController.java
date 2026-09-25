package com.wine.controller;

import com.wine.common.Result;
import com.wine.common.UserContextHolder;
import com.wine.dto.CreateOrderReq;
import com.wine.dto.OrderResp;
import com.wine.service.OrderService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 交易订单接口
 */
@RestController
@RequestMapping("/order")
public class OrderController {

    @Resource
    private OrderService orderService;

    @PostMapping("/create")
    public Result<OrderResp> create(@Valid @RequestBody CreateOrderReq req,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Result.success(orderService.createOrder(UserContextHolder.getUserId(), req, idempotencyKey));
    }

    @GetMapping("/{orderNo}")
    public Result<OrderResp> detail(@PathVariable String orderNo) {
        return Result.success(orderService.getOrder(orderNo));
    }
}
