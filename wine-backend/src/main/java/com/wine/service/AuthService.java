package com.wine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.common.BusinessException;
import com.wine.common.JwtUtil;
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

    @Value("${wine.age.legal-age:18}")
    private int legalAge;

    /**
     * 发送短信验证码
     * （一期对接国际短信网关，此处简化为生成6位码并入库）
     */
    public void sendSmsCode(SmsSendReq req) {
        String phone = req.getPhone();
        String code = String.format("%06d", new Random().nextInt(1000000));

        SmsOtpLog logEntry = new SmsOtpLog();
        logEntry.setPhone(phone);
        logEntry.setCode(code);
        logEntry.setExpireTime(LocalDateTime.now().plusMinutes(5));
        logEntry.setUsed(false);
        logEntry.setSendResult("模拟发送成功, code=" + code);
        smsOtpLogMapper.insert(logEntry);

        log.info("发送短信验证码: phone={}, code={}", phone, code);
    }

    /**
     * 登录（SMS OTP / Apple / Google）
     */
    @Transactional
    public LoginResp login(LoginReq req) {
        SysUser user;
        if (req.getLoginType() != null && req.getLoginType() == LoginTypeEnum.SMS_OTP.getCode()) {
            // 校验验证码
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

            // 查找或创建用户
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
            }
        }

        LoginResp resp = new LoginResp();
        resp.setToken(jwtUtil.generateToken(user.getId(), user.getRole()));
        resp.setUserId(user.getId());
        resp.setRole(user.getRole());
        resp.setAgeVerified(user.getAgeVerified());
        resp.setNickname(user.getNickname());
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
