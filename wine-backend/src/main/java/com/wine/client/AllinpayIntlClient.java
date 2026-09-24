package com.wine.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wine.common.BusinessException;
import com.wine.config.AllinpayIntlConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 通联国际（Allinpay International）CNP 客户端
 * <p>
 * 对接 allinpayhk.com 海外收单网关，支持国际信用卡 + 3DS 2.0 鉴权。
 * <p>
 * 与国内通联（syb.allinpay.com）的关键差异：
 * 1. 签名算法：RSA2（SHA256withRSA），非 SHA1withRSA
 * 2. 金额单位：元（如 18.00），非分
 * 3. 币种：支持 SGD 等 140+ 币种
 * 4. 模式：页面跳转模式（Gateway Forward），卡号/CVV 由通联收银台采集
 * 5. 3DS：收银台自动完成 3DS 2.0 鉴权
 * <p>
 * 文档：通华收银宝 CNP 产品接口规范 V2.0.0
 */
@Slf4j
@Component
public class AllinpayIntlClient {

    @Resource
    private AllinpayIntlConfig config;

    @Resource
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ==================== 签名 / 验签 ====================

    /**
     * 商户私钥签名（RSA2 = SHA256withRSA）
     * 签名串：除 sign 外所有非空字段按 ASCII 升序 key=value& 拼接
     */
    public String sign(Map<String, String> params) {
        String content = buildSignContent(params);
        try {
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(config.getPrivateKey()));
            PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(spec);
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(pk);
            sig.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            log.error("通联国际签名失败: {}", e.getMessage());
            throw new BusinessException("通联国际签名失败：" + e.getMessage());
        }
    }

    /**
     * 通联国际公钥验签（RSA2 = SHA256withRSA）
     * mock 模式下跳过验签，便于本地测试异步通知
     */
    public boolean verify(Map<String, String> params) {
        if (config.isMock()) {
            log.info("[MOCK] 通联国际验签跳过");
            return true;
        }
        String sign = params.get("sign");
        if (sign == null || sign.isEmpty()) {
            return false;
        }
        Map<String, String> tmp = new HashMap<>(params);
        tmp.remove("sign");
        String content = buildSignContent(tmp);
        try {
            X509EncodedKeySpec spec = new X509EncodedKeySpec(Base64.getDecoder().decode(config.getPublicKey()));
            PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(spec);
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(pub);
            sig.update(content.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(sign));
        } catch (Exception e) {
            log.error("通联国际验签失败: {}", e.getMessage());
            return false;
        }
    }

    private String buildSignContent(Map<String, String> params) {
        return params.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .filter(e -> !"sign".equals(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
    }

    // ==================== 业务接口 ====================

    /**
     * 页面跳转模式消费下单，返回收银台 payUrl
     * <p>
     * 持卡人在通联收银台页面输入卡号/有效期/CVV，由通联完成 3DS 2.0 鉴权与授权。
     * 商户侧不触碰任何卡数据 → 满足 PCI DSS 合规。
     *
     * @param accessOrderId 商户订单号
     * @param amount        金额（元，如 18.00）
     * @param subject       订单标题
     * @return 通联收银台 payUrl，前端跳转该地址完成支付
     */
    public String createOrder(String accessOrderId, BigDecimal amount, String subject) {
        if (config.isMock()) {
            return mockPayUrl(accessOrderId);
        }

        Map<String, String> params = new TreeMap<>();
        params.put("version", config.getVersion());
        params.put("instNo", config.getInstNo());
        params.put("mchtId", config.getMchtId());
        params.put("transType", config.getTransTypePurchase());
        params.put("accessOrderId", accessOrderId);
        params.put("amount", amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        params.put("currency", config.getCurrency());
        params.put("notifyUrl", config.getNotifyUrl());
        params.put("returnUrl", config.getReturnUrl());
        params.put("subject", subject);
        params.put("payPageStyle", config.getPayPageStyle());
        params.put("signType", "RSA2");
        params.put("sign", sign(params));

        log.info("通联国际下单请求: accessOrderId={}, amount={} {}, currency={}, transType={}",
                accessOrderId, params.get("amount"), config.getCurrency(), config.getTransTypePurchase());

        String resp = postForm(config.getBaseUrl() + "/cnp/quickpay", params);
        Map<String, String> result = parseJson(resp);

        String resultCode = result.get("resultCode");
        if (!"SUCCESS".equals(resultCode) && !"0000".equals(resultCode)) {
            throw new BusinessException("通联国际下单失败：" + result.getOrDefault("resultDesc", "未知错误"));
        }
        String payUrl = result.get("payUrl");
        if (payUrl == null || payUrl.isEmpty()) {
            throw new BusinessException("通联国际未返回收银台地址：" + resp);
        }
        log.info("通联国际下单成功: accessOrderId={}, payUrl={}", accessOrderId, payUrl);
        return payUrl;
    }

    /**
     * 交易结果查询
     *
     * @param accessOrderId 商户订单号
     */
    public Map<String, String> queryOrder(String accessOrderId) {
        if (config.isMock()) {
            Map<String, String> mock = new HashMap<>();
            mock.put("resultCode", "SUCCESS");
            mock.put("accessOrderId", accessOrderId);
            mock.put("transType", config.getTransTypeQuery());
            mock.put("resultDesc", "mock-success");
            return mock;
        }

        Map<String, String> params = new TreeMap<>();
        params.put("version", config.getVersion());
        params.put("instNo", config.getInstNo());
        params.put("mchtId", config.getMchtId());
        params.put("transType", config.getTransTypeQuery());
        params.put("accessOrderId", accessOrderId);
        params.put("signType", "RSA2");
        params.put("sign", sign(params));

        String resp = postForm(config.getBaseUrl() + "/cnp/quickpay", params);
        Map<String, String> result = parseJson(resp);
        if (!verify(result)) {
            throw new BusinessException("通联国际查询响应验签失败");
        }
        log.info("通联国际交易查询: accessOrderId={}, resultCode={}", accessOrderId, result.get("resultCode"));
        return result;
    }

    /**
     * 退款
     *
     * @param accessOrderId    退款订单号
     * @param oriAccessOrderId 原消费订单号
     * @param amount           退款金额（元）
     */
    public Map<String, String> refund(String accessOrderId, String oriAccessOrderId, BigDecimal amount) {
        if (config.isMock()) {
            Map<String, String> mock = new HashMap<>();
            mock.put("resultCode", "SUCCESS");
            mock.put("accessOrderId", accessOrderId);
            mock.put("oriAccessOrderId", oriAccessOrderId);
            mock.put("resultDesc", "mock-refund-success");
            return mock;
        }

        Map<String, String> params = new TreeMap<>();
        params.put("version", config.getVersion());
        params.put("instNo", config.getInstNo());
        params.put("mchtId", config.getMchtId());
        params.put("transType", config.getTransTypeRefund());
        params.put("accessOrderId", accessOrderId);
        params.put("oriAccessOrderId", oriAccessOrderId);
        params.put("amount", amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        params.put("currency", config.getCurrency());
        params.put("notifyUrl", config.getNotifyUrl());
        params.put("signType", "RSA2");
        params.put("sign", sign(params));

        String resp = postForm(config.getBaseUrl() + "/cnp/quickpay", params);
        Map<String, String> result = parseJson(resp);
        if (!verify(result)) {
            throw new BusinessException("通联国际退款响应验签失败");
        }
        String resultCode = result.get("resultCode");
        if (!"SUCCESS".equals(resultCode) && !"0000".equals(resultCode)) {
            throw new BusinessException("通联国际退款失败：" + result.getOrDefault("resultDesc", "未知错误"));
        }
        log.info("通联国际退款成功: accessOrderId={}, oriAccessOrderId={}", accessOrderId, oriAccessOrderId);
        return result;
    }

    // ==================== 工具 ====================

    /**
     * mock 模式：返回模拟收银台地址，不实际调用通联
     */
    private String mockPayUrl(String accessOrderId) {
        String mockUrl = "https://test.allinpayhk.com/pay-web-h5/mock?orderId=" + accessOrderId
                + "&mchtId=" + config.getMchtId();
        log.info("[MOCK] 通联国际模拟下单: accessOrderId={}, mockPayUrl={}", accessOrderId, mockUrl);
        return mockUrl;
    }

    private String randomStr() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String postForm(String url, Map<String, String> params) {
        String form = params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            log.info("通联国际响应: url={}, status={}, body={}", url, response.statusCode(), body);
            return body;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用通联国际接口异常: url={}, msg={}", url, e.getMessage());
            throw new BusinessException("调用通联国际接口失败：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseJson(String json) {
        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            Map<String, String> result = new HashMap<>();
            raw.forEach((k, v) -> result.put(k, v == null ? "" : String.valueOf(v)));
            return result;
        } catch (Exception e) {
            log.error("解析通联国际响应失败: json={}, err={}", json, e.getMessage());
            throw new BusinessException("解析通联国际响应失败：" + e.getMessage());
        }
    }
}
