package com.wine.enums;

import lombok.Getter;

/**
 * 用户角色类型
 * 多租户权限模型
 */
@Getter
public enum UserRoleEnum {

    CUSTOMER(1, "C端消费者"),
    BAR_ADMIN(2, "酒吧管理员"),
    PLATFORM_ADMIN(5, "平台运营超管"),
    SUPPLIER(6, "酒商供应商");

    private final int code;
    private final String name;

    UserRoleEnum(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public static UserRoleEnum of(int code) {
        for (UserRoleEnum e : values()) {
            if (e.code == code) return e;
        }
        return null;
    }
}
