package com.wine.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 分酒机瓶位表
 * 物理瓶位（1~8号），记录当前在机酒款与余量
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dispenser_slot")
public class DispenserSlot extends BaseEntity {

    /** 分酒机ID */
    private Long dispenserId;

    /** 瓶位号（1~8） */
    private Integer slotNo;

    /** 酒款SKU ID */
    private Long wineSkuId;

    /** 初始总容量（ml），如 750 */
    private Integer initialCapacity;

    /** 当前剩余容量（ml） */
    private Integer currentCapacity;

    /** 批次号 */
    private String batchNo;

    /** 上一瓶残留量（ml），<=10ml 为合理损耗 */
    private Integer residualMl;

    /** 是否需要校准检修（损耗>5ml标记） */
    private Boolean needCalibration;
}
