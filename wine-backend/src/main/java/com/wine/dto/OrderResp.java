package com.wine.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderResp {
    private String orderNo;
    private Integer status;
    private String statusName;
    private Long barId;
    private String barName;
    private Long dispenserId;
    private Integer slotNo;
    private Long wineSkuId;
    private String wineName;
    private Integer volumeMl;
    private BigDecimal originalAmount;
    private BigDecimal discountAmount;
    private BigDecimal paidAmount;
    private Integer payStatus;
    private LocalDateTime createTime;
    private LocalDateTime payExpireTime;
    /** 通联收银台URL（支付中返回） */
    private String cashierUrl;
}
