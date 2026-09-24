package com.wine.enums;

import lombok.Getter;

/**
 * 硬件出酒履约单状态机
 * 线下机械出酒域
 */
@Getter
public enum DispenseTicketStatusEnum {

    READY(1, "就绪待出", "分酒机状态正常且检测到酒杯存在(cup_present=1)"),
    DISPENSING(2, "出酒中", "云端携带order_id幂等键下发出酒指令"),
    SUCCESS(3, "已出酒", "分酒机成功回传出酒完毕，履约终态"),
    FAILED(4, "出酒失败", "超时未响应/硬件报错/actual_ml不足，履约终态");

    private final int code;
    private final String name;
    private final String desc;

    DispenseTicketStatusEnum(int code, String name, String desc) {
        this.code = code;
        this.name = name;
        this.desc = desc;
    }

    public static DispenseTicketStatusEnum of(int code) {
        for (DispenseTicketStatusEnum e : values()) {
            if (e.code == code) return e;
        }
        return null;
    }
}
