package com.wine.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 通联支付配置
 * - private-key: 商户 RSA 私钥（PKCS8 Base64），用于请求签名
 * - public-key:  通联 RSA 公钥（Base64），用于响应/回调验签
 * - 实际资金清算以订单 cusid（酒吧子商户号）为准
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "allinpay")
public class AllinpayConfig {

    /** 机构号 orgid */
    private String orgId;

    /** 平台主商户号 cusid（兜底，实际以订单 cusid 为准） */
    private String cusId;

    /** 应用ID appid */
    private String appId;

    /** 接口版本 */
    private String version = "12";

    /** true=测试环境 syb-test，false=生产环境 syb */
    private boolean sandbox = true;

    /** 商户 RSA 私钥（PKCS8 Base64），请求签名用 */
    private String privateKey;

    /** 通联 RSA 公钥（Base64），响应/回调验签用 */
    private String publicKey;

    /** 异步通知地址（公网可达） */
    private String notifyUrl;

    /** 前端支付完成回跳地址 */
    private String returnUrl;

    /** 支付有效时长（分钟） */
    private int validMinutes = 30;

    /** 网关根地址 */
    public String getBaseUrl() {
        return sandbox ? "https://syb-test.allinpay.com" : "https://syb.allinpay.com";
    }
}
