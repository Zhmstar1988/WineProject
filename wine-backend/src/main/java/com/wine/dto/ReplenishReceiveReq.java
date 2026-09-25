package com.wine.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 到货验收请求
 */
@Data
public class ReplenishReceiveReq {

    @NotNull(message = "实收数量不能为空")
    @Min(value = 0, message = "实收数量不能为负")
    private Integer receivedQty;

    private String remark;
}
