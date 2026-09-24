package com.wine.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 硬件出酒履约单表
 * 线下机械出酒域，状态机：READY -> DISPENSING -> SUCCESS / FAILED
 * 与交易主订单 1:1 关联，通过 order_id（orderNo）物理幂等
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dispense_ticket")
public class DispenseTicket extends BaseEntity {

    /** 关联交易主订单号（幂等键） */
    private String orderNo;

    /** 主订单ID */
    private Long orderId;

    /** 用户ID */
    private Long userId;

    /** 分酒机ID */
    private Long dispenserId;

    /** 瓶位号 */
    private Integer slotNo;

    /** 目标出酒量（ml） */
    private Integer targetMl;

    /** 实际出酒量（ml，分酒机回传） */
    private Integer actualMl;

    /** 履约单状态: 1-就绪待出 2-出酒中 3-已出酒 4-出酒失败 */
    private Integer status;

    /** 杯位传感器标识: 1-有杯 0-无杯 */
    private Boolean cupPresent;

    /** 出酒指令下发时间 */
    private LocalDateTime dispenseStartTime;

    /** 出酒完成时间 */
    private LocalDateTime dispenseEndTime;

    /** 失败原因 */
    private String failReason;

    /** SLA是否达标（指令响应<=3s，出酒<=30s） */
    private Boolean slaMet;
}
