package com.wine.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建补货单请求
 */
@Data
public class ReplenishCreateReq {

    @NotNull(message = "酒吧ID不能为空")
    private Long barId;

    private Long supplierId;

    @NotNull(message = "酒款SKU不能为空")
    private Long wineSkuId;

    @NotNull(message = "补货数量不能为空")
    @Min(value = 1, message = "补货数量至少1瓶")
    private Integer quantity;

    private BigDecimal unitPrice;

    private String remark;
}
