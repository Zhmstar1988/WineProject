package com.wine.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 通联国际（Allinpay International）CNP 产品配置
 * 对接 allinpayintl.com 海外收单网关，支持国际信用卡（Visa/Mastercard/Amex/JCB）
 * <p>
 * 合规要点（新加坡市场）：
 * 1. 页面跳转模式：卡号/CVV 由通联收银台采集，商户侧不触碰卡数据 → 满足 PCI DSS
 * 2. 3DS 2.0 鉴权由通联收银台自动完成 → 满足 SCA 强客户认证
 * 3. 币种支持 SGD（新加坡元）
 * 4. 异步通知失败重试最多 8 次
 * <p>
 * 文档：通华收银宝 CNP 产品接口规范 V2.0.0
 * 测试网关：https://test.allinpayhk.com/gateway
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "allinpay-intl")
public class AllinpayIntlConfig {

    /** 通联国际网关根地址（测试：https://test.allinpayhk.com/gateway） */
    private String baseUrl;

    /** 接入号 instNo（8位数字，通联国际分配，可选） */
    private String instNo;

    /** 商户号 mchtId（15位，通联国际分配，必填） */
    private String mchtId;

    /** 接口版本，固定 V2.0.0 */
    private String version = "V2.0.0";

    /** 交易币种，新加坡市场默认 SGD */
    private String currency = "SGD";

    /** 交易类型 transType（消费-跳转网关，具体取值以通联附录A为准） */
    private String transTypePurchase = "Purchase";

    /** 交易类型 transType（查询） */
    private String transTypeQuery = "Query";

    /** 交易类型 transType（退款） */
    private String transTypeRefund = "Refund";

    /** 商户 RSA 私钥（PKCS8 Base64），用于 RSA2(SHA256withRSA) 加签 */
    private String privateKey;

    /** 通联国际 RSA 公钥（X.509 Base64），用于响应/回调验签 */
    private String publicKey;

    /** 异步通知地址（公网可达，HTTPS） */
    private String notifyUrl;

    /** 前端支付完成回跳地址（通联收银台支付后跳转） */
    private String returnUrl;

    /** 收银台页面风格：TINY（仅卡信息）/ DEFAULT（含账单信息可修改） */
    private String payPageStyle = "TINY";

    /** mock 模式：true 时不实际调用通联接口，返回模拟收银台地址，用于无商户号时的本地测试 */
    private boolean mock = true;
}
