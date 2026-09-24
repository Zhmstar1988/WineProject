package com.wine.controller;

import com.wine.common.Result;
import com.wine.service.DispenseService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 分酒机出酒履约接口
 */
@RestController
@RequestMapping("/dispense")
public class DispenseController {

    @Resource
    private DispenseService dispenseService;

    /** 查询分酒机就绪状态（杯位感应） */
    @GetMapping("/status/{dispenserId}/{slotNo}")
    public Result<Map<String, Object>> checkStatus(@PathVariable Long dispenserId,
                                                    @PathVariable Integer slotNo) {
        return Result.success(dispenseService.checkStatus(dispenserId, slotNo));
    }

    /** 一键出酒（携带 order_id 幂等键） */
    @PostMapping("/start/{orderNo}")
    public Result<Void> startDispense(@PathVariable String orderNo) {
        dispenseService.startDispense(orderNo);
        return Result.success();
    }

    /** 出酒前取消 */
    @PostMapping("/cancel/{orderNo}")
    public Result<Void> cancel(@PathVariable String orderNo) {
        dispenseService.cancelBeforeDispense(orderNo);
        return Result.success();
    }

    /** 分酒机出酒结果回调 */
    @PostMapping("/callback")
    public Result<Void> callback(@RequestBody Map<String, Object> params) {
        dispenseService.handleCallback(params);
        return Result.success();
    }
}
