package com.wine.enums;

import lombok.Getter;

/**
 * 分酒机设备状态
 */
@Getter
public enum DispenserStatusEnum {

    OFFLINE(0, "离线"),
    ONLINE(1, "在线"),
    FAULT(2, "故障");

    private final int code;
    private final String name;

    DispenserStatusEnum(int code, String name) {
        this.code = code;
        this.name = name;
    }
}
