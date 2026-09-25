package com.wine.dto;

import lombok.Data;

@Data
public class LoginResp {
    private String token;
    private Long userId;
    private Integer role;
    private Boolean ageVerified;
    private String nickname;
    /** 是否为新注册用户（首次登录自动创建），APP端据此引导完善注册流程 */
    private Boolean isNewUser;
}
