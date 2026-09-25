package com.wine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.common.BusinessException;
import com.wine.common.JwtUtil;
import com.wine.config.TwilioConfig;
import com.wine.domain.SmsOtpLog;
import com.wine.domain.SysUser;
import com.wine.dto.AgeVerifyReq;
import com.wine.dto.LoginReq;
import com.wine.dto.LoginResp;
import com.wine.dto.SmsSendReq;
import com.wine.enums.LoginTypeEnum;
import com.wine.enums.UserRoleEnum;
import com.wine.mapper.SmsOtpLogMapper;
import com.wine.mapper.SysUserMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.Random;

/**
 * 认证与用户服务
 * 一期：SMS OTP + Apple/Google 登录 + 出生日期年龄校验
 * 合规：严禁采集 NRIC
 */
@Slf4j
@Service
public class AuthService {

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private SmsOtpLogMapper smsOtpLogMapper;

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private TwilioSmsService twilioSmsService;

    @Resource
    private TwilioConfig twilioConfig;

    @Value("${wine.age.legal-age:18}")
    private int legalAge;

    /**
     * 发送短信验证码
     * <p>
     * 优先使用 Twilio Verify API（配置 verify-service-sid 时），验证码由 Twilio 生成与下发，
     * 自动使用合规发送者 ID，本地不存储明文验证码；
     * 未配置 verify-service-sid 时降级为本地生成验证码 + 普通短信发送。
     * Twilio 未启用时两种模式均为 mock（本地生成，可从 sms_otp_log 表查询）。
     */
    public void sendSmsCode(SmsSendReq req) {
        String phone = req.getPhone();
        String sendResult;
        String code = null;

        boolean useVerify = twilioConfig.getVerifyServiceSid() != null
                && !twilioConfig.getVerifyServiceSid().isBlank();

        try {
            if (useVerify) {
                // Verify API 模式：Twilio 生成并下发验证码
                String sid = twilioSmsService.sendVerification(phone);
                sendResult = "verify发送成功, sid=" + sid;
            } else {
                // 普通短信模式：本地生成验证码
                code = String.format("%06d", new Random().nextInt(1000000));
                String body = "您的验证码是 " + code + "，5分钟内有效，请勿泄露。";
                String sid = twilioSmsService.sendSms(phone, body);
                sendResult = "发送成功, sid=" + sid;
            }
        } catch (Exception e) {
            // 发送失败时仍入库（mock 模式或测试阶段可从数据库获取验证码）
            if (code == null) {
                code = String.format("%06d", new Random().nextInt(1000000));
            }
            sendResult = "发送失败: " + e.getMessage();
            log.error("短信发送失败，验证码已入库: phone={}, code={}", phone, code);
        }

        SmsOtpLog logEntry = new SmsOtpLog();
        logEntry.setPhone(phone);
        // Verify 模式下 Twilio 不返回明文验证码，code 字段留空；mock/普通短信模式存储本地生成的验证码
        logEntry.setCode(code);
        logEntry.setExpireTime(LocalDateTime.now().plusMinutes(5));
        logEntry.setUsed(false);
        logEntry.setSendResult(code != null ? sendResult + ", code=" + code : sendResult);
        smsOtpLogMapper.insert(logEntry);

        log.info("发送短信验证码: phone={}, mode={}, result={}", phone, useVerify ? "Verify" : "SMS", sendResult);
    }

    /**
     * 登录（SMS OTP / Apple / Google）
     */
    @Transactional
    public LoginResp login(LoginReq req) {
        SysUser user;
        boolean isNewUser = false;
        if (req.getLoginType() != null && req.getLoginType() == LoginTypeEnum.SMS_OTP.getCode()) {
            // 校验验证码
            boolean useVerify = twilioConfig.getVerifyServiceSid() != null
                    && !twilioConfig.getVerifyServiceSid().isBlank();
            boolean valid;

            if (useVerify) {
                // Verify 模式：调用 Twilio 校验
                valid = twilioSmsService.checkVerification(req.getPhone(), req.getCode());
                if (!valid) {
                    throw new BusinessException("验证码无效或已过期");
                }
            } else {
                // 普通短信/mock 模式：本地数据库校验
                SmsOtpLog otp = smsOtpLogMapper.selectOne(
                        new LambdaQueryWrapper<SmsOtpLog>()
                                .eq(SmsOtpLog::getPhone, req.getPhone())
                                .eq(SmsOtpLog::getCode, req.getCode())
                                .eq(SmsOtpLog::getUsed, false)
                                .gt(SmsOtpLog::getExpireTime, LocalDateTime.now())
                                .orderByDesc(SmsOtpLog::getCreateTime)
                                .last("LIMIT 1"));
                if (otp == null) {
                    throw new BusinessException("验证码无效或已过期");
                }
                otp.setUsed(true);
                smsOtpLogMapper.updateById(otp);
                valid = true;
            }

            // 查找或创建用户（C端用户仅能通过APP侧手机号注册，后台不可新建）
            user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, req.getPhone()));
            if (user == null) {
                user = new SysUser();
                user.setPhone(req.getPhone());
                user.setLoginType(LoginTypeEnum.SMS_OTP.getCode());
                user.setRole(UserRoleEnum.CUSTOMER.getCode());
                user.setNickname("用户" + req.getPhone().substring(Math.max(0, req.getPhone().length() - 4)));
                user.setAgeVerified(false);
                user.setStatus(1);
                sysUserMapper.insert(user);
                isNewUser = true; // 标记为新注册用户，APP端引导切换到注册完善流程
            }
        } else {
            // 三方登录
            user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getOpenid, req.getOpenid()));
            if (user == null) {
                user = new SysUser();
                user.setOpenid(req.getOpenid());
                user.setLoginType(req.getLoginType());
                user.setRole(UserRoleEnum.CUSTOMER.getCode());
                user.setNickname(req.getNickname());
                user.setAvatar(req.getAvatar());
                user.setAgeVerified(false);
                user.setStatus(1);
                sysUserMapper.insert(user);
                isNewUser = true; // 三方登录首次即为注册
            }
        }

        LoginResp resp = new LoginResp();
        resp.setToken(jwtUtil.generateToken(user.getId(), user.getRole()));
        resp.setUserId(user.getId());
        resp.setRole(user.getRole());
        resp.setAgeVerified(user.getAgeVerified());
        resp.setNickname(user.getNickname());
        resp.setIsNewUser(isNewUser);
        return resp;
    }

    /**
     * 法定年龄校验（一期手动输入出生日期）
     * 仅记录 age_verified=true 标记，不存储证件号
     */
    @Transactional
    public void verifyAge(Long userId, AgeVerifyReq req) {
        LocalDate birthDate = req.getBirthDate();
        int age = Period.between(birthDate, LocalDate.now()).getYears();
        if (age < legalAge) {
            throw new BusinessException("未满" + legalAge + "周岁，禁止购买酒精饮品");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        user.setAgeVerified(true);
        user.setBirthDate(birthDate);
        sysUserMapper.updateById(user);
    }
}
