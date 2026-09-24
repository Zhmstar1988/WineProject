package com.wine.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginReq {
    /** 手机号 */
    private String phone;
    /** 验证码 */
    private String code;
    /** 三方登录标识 (Apple/Google) */
    private String openid;
    /** 登录方式: 1-SMS 2-Apple 3-Google */
    private Integer loginType;
    /** 昵称 */
    private String nickname;
    /** 头像 */
    private String avatar;
}
