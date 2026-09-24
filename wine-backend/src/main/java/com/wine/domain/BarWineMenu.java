package com.wine.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 酒吧酒单（酒吧×酒款×杯量规格 关联表）
 * 每款酒支持不同杯量规格及对应售价
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bar_wine_menu")
public class BarWineMenu extends BaseEntity {

    /** 酒吧ID */
    private Long barId;

    /** 分酒机ID */
    private Long dispenserId;

    /** 瓶位ID */
    private Long slotId;

    /** 酒款SKU ID */
    private Long wineSkuId;

    /** 杯量规格（ml）：50 品尝杯 / 150 标准杯 */
    private Integer volumeMl;

    /** 杯量名称 */
    private String volumeName;

    /** 售价（SGD） */
    private BigDecimal price;

    /** 状态: 1-可售 0-不可售 */
    private Integer status;
}
