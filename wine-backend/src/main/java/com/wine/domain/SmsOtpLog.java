package com.wine.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 短信验证码日志表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sms_otp_log")
public class SmsOtpLog extends BaseEntity {

    /** 手机号 */
    private String phone;

    /** 验证码 */
    private String code;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /** 是否已使用 */
    private Boolean used;

    /** 发送结果 */
    private String sendResult;
}
