package com.wine.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 酒吧表
 * 40家存量酒吧，每家对应通联子商户 cusid
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bar")
public class Bar extends BaseEntity {

    /** 酒吧编码，如 SG_BAR_01 */
    private String barCode;

    /** 酒吧名称 */
    private String barName;

    /** 地址 */
    private String address;

    /** 联系电话 */
    private String contactPhone;

    /** 通联子商户号 cusid */
    private String cusid;

    /** 进件状态: 0-未进件 1-进件中 2-已通过 3-已驳回 */
    private Integer merchantStatus;

    /** 酒吧状态: 1-营业中 0-已停业 */
    private Integer status;

    /** 4位酒吧码（APP兜底手动输入） */
    private String shortCode;
}
