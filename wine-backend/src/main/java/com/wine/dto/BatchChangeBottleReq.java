package com.wine.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量换瓶请求：一次给分酒机的多个瓶位同时换瓶
 * 按酒款聚合扣减后备库存，任一酒款库存不足则全部回滚
 */
@Data
public class BatchChangeBottleReq {

    @NotNull(message = "分酒机ID不能为空")
    private Long dispenserId;

    @NotEmpty(message = "换瓶列表不能为空")
    @Valid
    private List<ChangeBottleItem> items;

    @Data
    public static class ChangeBottleItem {
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
}
