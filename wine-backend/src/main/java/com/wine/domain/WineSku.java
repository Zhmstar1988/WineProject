package com.wine.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 酒款SKU表
 * 上游酒商供应的红酒品类
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wine_sku")
public class WineSku extends BaseEntity {

    /** 酒款名称 */
    private String wineName;

    /** 产区/国家 */
    private String origin;

    /** 年份 */
    private Integer vintage;

    /** 葡萄品种 */
    private String grapeType;

    /** 酒精度 */
    private String alcohol;

    /** 供应酒商ID */
    private Long supplierId;

    /** 封面图 */
    private String coverImage;

    /** 简介 */
    private String description;

    /** 状态: 1-在售 0-下架 */
    private Integer status;
}
