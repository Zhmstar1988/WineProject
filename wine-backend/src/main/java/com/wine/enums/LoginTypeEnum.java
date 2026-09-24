package com.wine.enums;

import lombok.Getter;

/**
 * 登录方式
 */
@Getter
public enum LoginTypeEnum {

    SMS_OTP(1, "手机号短信验证码"),
    APPLE(2, "Apple登录"),
    GOOGLE(3, "Google登录");

    private final int code;
    private final String name;

    LoginTypeEnum(int code, String name) {
        this.code = code;
        this.name = name;
    }
}
