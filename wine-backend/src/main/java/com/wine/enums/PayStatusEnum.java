package com.wine.enums;

import lombok.Getter;

/**
 * 支付状态（通联授权二元标记）
 */
@Getter
public enum PayStatusEnum {

    UNPAID(0, "未支付"),
    SUCCESS(1, "支付成功"),
    FAILED(2, "支付失败"),
    REFUNDED(3, "已退款");

    private final int code;
    private final String name;

    PayStatusEnum(int code, String name) {
        this.code = code;
        this.name = name;
    }
}
