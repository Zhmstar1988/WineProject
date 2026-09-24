package com.wine.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateOrderReq {
    @NotNull(message = "酒吧ID不能为空")
    private Long barId;

    @NotNull(message = "分酒机ID不能为空")
    private Long dispenserId;

    @NotNull(message = "瓶位号不能为空")
    private Integer slotNo;

    @NotNull(message = "酒款SKU不能为空")
    private Long wineSkuId;

    @NotNull(message = "杯量不能为空")
    private Integer volumeMl;
}
