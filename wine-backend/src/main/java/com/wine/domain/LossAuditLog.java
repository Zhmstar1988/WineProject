package com.wine.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 设备物理损耗审计日志表
 * 出酒不足差额、换瓶残留量等损耗记录
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("loss_audit_log")
public class LossAuditLog extends BaseEntity {

    /** 关联订单号 */
    private String orderNo;

    /** 分酒机ID */
    private Long dispenserId;

    /** 瓶位号 */
    private Integer slotNo;

    /** 损耗类型: 1-出酒不足差额 2-换瓶残留 */
    private Integer lossType;

    /** 目标量（ml） */
    private Integer targetMl;

    /** 实际量（ml） */
    private Integer actualMl;

    /** 损耗量（ml） */
    private Integer lossMl;

    /** 损耗说明 */
    private String remark;
}
