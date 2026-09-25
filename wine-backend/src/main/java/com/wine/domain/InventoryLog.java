package com.wine.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 库存变动流水：记录整瓶库存的每一次入库/出库/调整，用于追溯
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inventory_log")
public class InventoryLog extends BaseEntity {

    /** 酒吧ID */
    private Long barId;

    /** 酒款SKU */
    private Long wineSkuId;

    /** 变动类型: 1-入库(验收) 2-出库(换瓶) 3-手动调整 4-拒收冲正 */
    private Integer changeType;

    /** 变动数量（正为入库，负为出库） */
    private Integer changeQty;

    /** 变动前库存 */
    private Integer beforeQty;

    /** 变动后库存 */
    private Integer afterQty;

    /** 关联类型: replenish/change_bottle/manual */
    private String refType;

    /** 关联单号 */
    private String refNo;

    /** 备注 */
    private String remark;
}
