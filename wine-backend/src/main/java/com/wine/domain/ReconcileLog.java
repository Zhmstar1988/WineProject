package com.wine.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 履约三单核对日志表
 * T+1 跑批：主订单 ↔ 履约单 ↔ 通联授权状态
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("reconcile_log")
public class ReconcileLog extends BaseEntity {

    /** 核对日期 */
    private LocalDate reconcileDate;

    /** 关联主订单号 */
    private String orderNo;

    /** 主订单状态 */
    private Integer orderStatus;

    /** 履约单状态 */
    private Integer ticketStatus;

    /** 通联支付状态: 1-SUCCESS 0-FAIL */
    private Integer payStatus;

    /** 核对结果: 1-一致 2-异常A少出漏单 3-异常B盗刷飞单 4-异常C机械损耗 */
    private Integer reconcileResult;

    /** 异常描述 */
    private String anomalyDesc;

    /** 是否已处理 */
    private Boolean handled;
}
