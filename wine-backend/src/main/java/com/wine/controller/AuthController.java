package com.wine.controller;

import com.wine.common.Result;
import com.wine.common.UserContextHolder;
import com.wine.dto.AgeVerifyReq;
import com.wine.dto.LoginReq;
import com.wine.dto.LoginResp;
import com.wine.dto.SmsSendReq;
import com.wine.service.AuthService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 认证与登录接口
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private AuthService authService;

    @PostMapping("/sms/send")
    public Result<Void> sendSms(@Valid @RequestBody SmsSendReq req) {
        authService.sendSmsCode(req);
        return Result.success();
    }

    @PostMapping("/login")
    public Result<LoginResp> login(@Valid @RequestBody LoginReq req) {
        return Result.success(authService.login(req));
    }

    @PostMapping("/age-verify")
    public Result<Void> verifyAge(@Valid @RequestBody AgeVerifyReq req) {
        authService.verifyAge(UserContextHolder.getUserId(), req);
        return Result.success();
    }
}
