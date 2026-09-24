package com.wine.dto;

import lombok.Data;

@Data
public class LoginResp {
    private String token;
    private Long userId;
    private Integer role;
    private Boolean ageVerified;
    private String nickname;
}
