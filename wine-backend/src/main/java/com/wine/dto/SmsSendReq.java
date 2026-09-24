package com.wine.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SmsSendReq {
    @NotBlank(message = "手机号不能为空")
    private String phone;
}
