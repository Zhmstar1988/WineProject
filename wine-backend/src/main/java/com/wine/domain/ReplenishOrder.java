package com.wine.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 补货单：酒吧向酒商发起补货，酒商发货后酒吧验收入库
 * 状态流转：待发货(0) → 已发货(1) → 已验收(2) / 已拒收(3)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("replenish_order")
public class ReplenishOrder extends BaseEntity {

    /** 补货单号 */
    private String orderNo;

    /** 酒吧ID */
    private Long barId;

    /** 供应酒商ID */
    private Long supplierId;

    /** 酒款SKU */
    private Long wineSkuId;

    /** 补货数量（整瓶） */
    private Integer quantity;

    /** 单价 SGD */
    private BigDecimal unitPrice;

    /** 总金额 */
    private BigDecimal totalAmount;

    /** 状态: 0-待发货 1-已发货 2-已验收 3-已拒收 */
    private Integer status;

    /** 发货时间 */
    private LocalDateTime shippedTime;

    /** 验收时间 */
    private LocalDateTime receivedTime;

    /** 实收数量 */
    private Integer receivedQty;

    /** 拒收原因 */
    private String rejectReason;

    /** 备注 */
    private String remark;
}
