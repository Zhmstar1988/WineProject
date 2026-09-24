package com.wine.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChangeBottleReq {
    @NotNull(message = "分酒机ID不能为空")
    private Long dispenserId;

    @NotNull(message = "瓶位号不能为空")
    private Integer slotNo;

    @NotNull(message = "酒款SKU不能为空")
    private Long wineSkuId;

    @NotNull(message = "初始容量不能为空")
    private Integer initialCapacity;

    private String batchNo;

    /** 上一瓶残留量（ml），<=10 为合理损耗 */
    private Integer residualMl;
}
