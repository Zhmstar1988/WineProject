package com.wine.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易主订单表
 * 线上资金结算域，状态机：PENDING -> PAYING -> PAID -> COMPLETED / REFUNDED
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("order_main")
public class OrderMain extends BaseEntity {

    /** 订单号（全局唯一，兼作出酒幂等键 order_id） */
    private String orderNo;

    /** 用户ID */
    private Long userId;

    /** 酒吧ID */
    private Long barId;

    /** 通联子商户号 */
    private String cusid;

    /** 分酒机ID */
    private Long dispenserId;

    /** 瓶位号 */
    private Integer slotNo;

    /** 酒款SKU ID */
    private Long wineSkuId;

    /** 杯量（ml） */
    private Integer volumeMl;

    /** 原价（SGD） */
    private BigDecimal originalAmount;

    /** 优惠金额（SGD） */
    private BigDecimal discountAmount;

    /** 实付金额（SGD，通联分账基准） */
    private BigDecimal paidAmount;

    /** 订单状态: 1-待支付 2-支付中 3-已付款/待履约 4-已完成 5-已退款 */
    private Integer status;

    /** 支付状态: 0-未支付 1-成功 2-失败 3-已退款 */
    private Integer payStatus;

    /** 通联交易单号 */
    private String transactionId;

    /** 支付超时时间 */
    private LocalDateTime payExpireTime;

    /** 支付完成时间 */
    private LocalDateTime payTime;

    /** 订单完成时间 */
    private LocalDateTime completeTime;

    /** 退款时间 */
    private LocalDateTime refundTime;

    /** 退款单号 */
    private String refundNo;

    /** 幂等键（防重复下单，由客户端生成唯一ID） */
    private String idempotentKey;

    /** 备注 */
    private String remark;
}
