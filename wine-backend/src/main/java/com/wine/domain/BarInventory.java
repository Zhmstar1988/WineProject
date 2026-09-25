package com.wine.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 酒吧后备库存表（实物整瓶库存）
 * 管理酒吧仓库中未上瓶位的整瓶红酒库存，与在机可售状态分离。
 * 换瓶 SOP 时从后备库存扣减，补充到瓶位。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bar_inventory")
public class BarInventory extends BaseEntity {

    /** 酒吧ID */
    private Long barId;

    /** 酒款SKU ID */
    private Long wineSkuId;

    /** 库存数量（整瓶数） */
    private Integer quantity;

    /** 预警阈值：低于此值触发补货提醒 */
    private Integer alertThreshold;

    /** 存放位置（如仓库A区） */
    private String location;

    /** 状态: 1-正常 0-停用 */
    private Integer status;
}
