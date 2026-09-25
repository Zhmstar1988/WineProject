package com.wine.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Twilio 国际短信配置（新加坡市场）
 * <p>
 * 测试凭据获取：Twilio 控制台 -> Account -> API keys & tokens -> Test credentials
 * 测试环境下使用测试 SID/Token + 测试号码 +15005550006，不会真实下发短信，但 API 会返回成功 SID。
 * <p>
 * 生产环境需：
 * 1. 使用 Live Account SID / Auth Token
 * 2. 购买 Twilio 号码作为 from-number
 * 3. 确保目标号码已在 Twilio 控制台验证（沙盒期）或账号已升级
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "twilio")
public class TwilioConfig {

    /** 是否启用 Twilio 真实发送；false 时降级为本地 mock（仅入库不发送） */
    private boolean enabled = true;

    /** Account SID（以 AC 开头，API 请求 URL 中使用，必填） */
    private String accountSid;

    /** API Key SID（以 SK 开头，可选；配置后使用 API Key 认证而非 Auth Token） */
    private String apiKeySid;

    /** API Key Secret（配合 apiKeySid 使用） */
    private String apiKeySecret;

    /** Auth Token（Account SID 认证时使用，与 API Key 二选一） */
    private String authToken;

    /** 发信号码：测试号 +15005550006 始终返回成功；生产需购买 Twilio 号码 */
    private String fromNumber;

    /** 短信内容前缀（签名），新加坡合规要求避免酒类字样，建议使用中性名称 */
    private String prefix = "[SGVerify]";

    /**
     * Twilio Verify Service SID（VA 开头）。
     * 配置后使用 Verify API 发送/校验验证码（自动处理新加坡合规发送者 ID）；
     * 未配置时降级为 sendSms 普通短信 + 本地校验。
     * 创建：Twilio 控制台 -> Explore Products -> Verify -> Services -> Create new
     */
    private String verifyServiceSid;
}
