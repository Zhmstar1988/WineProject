package com.wine.service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.rest.verify.v2.service.Verification;
import com.twilio.rest.verify.v2.service.VerificationCheck;
import com.twilio.type.PhoneNumber;
import com.wine.config.TwilioConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Twilio 国际短信与验证码服务
 * <p>
 * 两种验证码模式：
 * 1. Verify API（推荐）：配置 verify-service-sid 后启用，Twilio 生成并下发验证码，
 *    自动使用合规发送者 ID（如 TWVerify），无需自购号码、无需本地存验证码。
 * 2. 普通短信：未配置 verify-service-sid 时使用 sendSms，本地生成验证码并校验。
 * <p>
 * 当 twilio.enabled=false 或凭据未配置时，两种模式均降级为 mock（本地生成+校验）。
 */
@Slf4j
@Service
public class TwilioSmsService {

    @Resource
    private TwilioConfig twilioConfig;

    private volatile boolean initialized = false;

    @PostConstruct
    public void init() {
        if (!twilioConfig.isEnabled()) {
            log.info("Twilio 未启用（twilio.enabled=false），使用 mock 模式");
            return;
        }
        String accountSid = twilioConfig.getAccountSid();
        String apiKeySid = twilioConfig.getApiKeySid();
        String apiKeySecret = twilioConfig.getApiKeySecret();
        String authToken = twilioConfig.getAuthToken();

        try {
            if (apiKeySid != null && !apiKeySid.isBlank() && apiKeySecret != null && !apiKeySecret.isBlank()) {
                if (accountSid == null || accountSid.isBlank()) {
                    log.error("使用 API Key 认证时必须配置 account-sid（AC 开头），降级为 mock 模式");
                    return;
                }
                Twilio.init(apiKeySid, apiKeySecret, accountSid);
                initialized = true;
                log.info("Twilio 初始化成功（API Key 模式），Account SID: {}", accountSid);
            } else if (accountSid != null && !accountSid.isBlank() && authToken != null && !authToken.isBlank()) {
                Twilio.init(accountSid, authToken);
                initialized = true;
                log.info("Twilio 初始化成功（Auth Token 模式），Account SID: {}", accountSid);
            } else {
                log.warn("Twilio 凭据不完整，降级为 mock 模式");
            }
        } catch (Exception e) {
            log.error("Twilio 初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 使用 Twilio Verify API 发送短信验证码
     *
     * @param to 接收号码（E.164 格式，如 +6591234567）
     * @return verification SID；mock 模式返回 "mock"
     */
    public String sendVerification(String to) {
        if (!initialized) {
            log.info("[Twilio Mock] Verify 发送验证码 to={}", to);
            return "mock";
        }
        String serviceSid = twilioConfig.getVerifyServiceSid();
        if (serviceSid == null || serviceSid.isBlank()) {
            throw new IllegalStateException("未配置 twilio.verify-service-sid，无法使用 Verify API");
        }
        try {
            Verification verification = Verification.creator(serviceSid, to, "sms").create();
            log.info("Twilio Verify 发送成功: to={}, sid={}, status={}", to, verification.getSid(), verification.getStatus());
            return verification.getSid();
        } catch (Exception e) {
            log.error("Twilio Verify 发送失败: to={}, error={}", to, e.getMessage());
            throw new RuntimeException("验证码发送失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 Twilio Verify API 校验验证码
     *
     * @param to   接收号码
     * @param code 用户输入的验证码
     * @return true=校验通过，false=校验失败
     */
    public boolean checkVerification(String to, String code) {
        if (!initialized) {
            // mock 模式下由调用方自行校验
            return false;
        }
        String serviceSid = twilioConfig.getVerifyServiceSid();
        if (serviceSid == null || serviceSid.isBlank()) {
            throw new IllegalStateException("未配置 twilio.verify-service-sid，无法使用 Verify API");
        }
        try {
            VerificationCheck check = VerificationCheck.creator(serviceSid)
                    .setTo(to)
                    .setCode(code)
                    .create();
            boolean approved = "approved".equalsIgnoreCase(check.getStatus());
            log.info("Twilio Verify 校验: to={}, status={}, approved={}", to, check.getStatus(), approved);
            return approved;
        } catch (Exception e) {
            log.error("Twilio Verify 校验失败: to={}, error={}", to, e.getMessage());
            return false;
        }
    }

    /**
     * 发送普通短信（非 Verify 模式下使用）
     *
     * @param to   接收号码（E.164 格式）
     * @param body 短信内容
     * @return Message SID；mock 模式返回 "mock"
     */
    public String sendSms(String to, String body) {
        if (!initialized) {
            log.info("[Twilio Mock] 发送短信 to={}, body={}", to, body);
            return "mock";
        }
        String fullBody = twilioConfig.getPrefix() + " " + body;
        try {
            Message message = Message.creator(
                    new PhoneNumber(to),
                    new PhoneNumber(twilioConfig.getFromNumber()),
                    fullBody
            ).create();
            log.info("Twilio 短信发送成功: to={}, sid={}, status={}", to, message.getSid(), message.getStatus());
            return message.getSid();
        } catch (Exception e) {
            log.error("Twilio 短信发送失败: to={}, error={}", to, e.getMessage());
            throw new RuntimeException("短信发送失败: " + e.getMessage(), e);
        }
    }
}
