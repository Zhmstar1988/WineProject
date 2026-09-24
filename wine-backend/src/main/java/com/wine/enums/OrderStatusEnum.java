package com.wine.enums;

import lombok.Getter;

/**
 * 交易主订单状态机
 * 线上资金结算域
 */
@Getter
public enum OrderStatusEnum {

    PENDING(1, "待支付", "按杯下单成功，尚未付款"),
    PAYING(2, "支付中", "已调起通联收银台，等待异步回调"),
    PAID(3, "已付款/待履约", "通联支付清算确认成功，生成出酒履约单"),
    COMPLETED(4, "已完成", "出酒履约单回传履约成功，终态"),
    REFUNDED(5, "已退款", "出酒前取消或出酒失败，终态");

    private final int code;
    private final String name;
    private final String desc;

    OrderStatusEnum(int code, String name, String desc) {
        this.code = code;
        this.name = name;
        this.desc = desc;
    }

    public static OrderStatusEnum of(int code) {
        for (OrderStatusEnum e : values()) {
            if (e.code == code) return e;
        }
        return null;
    }
}
