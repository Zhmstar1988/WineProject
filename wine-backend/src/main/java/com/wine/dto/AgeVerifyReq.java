package com.wine.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class AgeVerifyReq {
    @NotNull(message = "出生日期不能为空")
    private LocalDate birthDate;
}
