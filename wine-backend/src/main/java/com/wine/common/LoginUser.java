package com.wine.common;

import com.wine.domain.SysUser;
import lombok.Data;

/**
 * 当前登录用户上下文（线程级）
 */
@Data
public class LoginUser {
    private Long userId;
    private Integer role;
    private Long barId;
    private Long supplierId;
    private Boolean ageVerified;

    public static LoginUser from(SysUser user) {
        LoginUser lu = new LoginUser();
        lu.setUserId(user.getId());
        lu.setRole(user.getRole());
        lu.setBarId(user.getBarId());
        lu.setSupplierId(user.getSupplierId());
        lu.setAgeVerified(user.getAgeVerified());
        return lu;
    }
}
