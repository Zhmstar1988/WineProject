package com.wine.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wine.common.BusinessException;
import com.wine.config.AllinpayConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 通联支付网关客户端
 * 封装 RSA 签名/验签 + HTTP 调用，对接通联官方 API：
 * 1. H5 收银台下单  /apiweb/h5unionpay/unionorder
 * 2. 交易查询      /apiweb/tranx/query
 * 3. 交易退款      /apiweb/tranx/refund
 * 签名规则：除 sign 外所有非空字段按 ASCII 升序拼接，SHA1withRSA + Base64
 */
@Slf4j
@Component
public class AllinpayClient {

    @Resource
    private AllinpayConfig config;

    @Resource
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    // ==================== 签名 / 验签 ====================

    /**
     * 商户私钥签名
     */
    public String sign(Map<String, String> params) {
        String content = buildSignContent(params);
        try {
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(config.getPrivateKey()));
            PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(spec);
            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initSign(pk);
            sig.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            log.error("通联签名失败: {}", e.getMessage());
            throw new BusinessException("通联签名失败：" + e.getMessage());
        }
    }

    /**
     * 通联公钥验签
     */
    public boolean verify(Map<String, String> params) {
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
            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initVerify(pub);
            sig.update(content.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(sign));
        } catch (Exception e) {
            log.error("通联验签失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 构造签名串：除 sign 外非空字段按 ASCII 升序 key=value& 拼接
     */
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
     * H5 收银台下单，返回收银台 payurl
     *
     * @param cusid  酒吧子商户号
     * @param reqsn  商户订单号（orderNo）
     * @param trxamt 金额（分）
     * @param body   商品描述
     */
    public String createH5Order(String cusid, String reqsn, long trxamt, String body) {
        Map<String, String> params = new TreeMap<>();
        params.put("orgid", config.getOrgId());
        params.put("cusid", cusid);
        params.put("appid", config.getAppId());
        params.put("version", config.getVersion());
        params.put("trxamt", String.valueOf(trxamt));
        params.put("reqsn", reqsn);
        params.put("charset", "UTF-8");
        params.put("notify_url", config.getNotifyUrl());
        params.put("returl", config.getReturnUrl());
        params.put("body", body);
        params.put("randomstr", randomStr());
        // validtime: 有效时间，单位为分钟（通联规范）
        params.put("validtime", String.valueOf(config.getValidMinutes()));
        // 分账指令：资金中立，100% 直分酒吧子商户
        // 规范：type=01 按金额，金额单位为元（非分）
        String asinfoAmount = amountFenToYuan(trxamt);
        params.put("asinfo", cusid + ":01:" + asinfoAmount);
        params.put("signtype", "RSA");
        params.put("sign", sign(params));

        log.info("通联下单请求参数: cusid={}, reqsn={}, trxamt={}分, validtime={}分钟, asinfo={}, charset={}",
                cusid, reqsn, trxamt, params.get("validtime"), params.get("asinfo"), params.get("charset"));

        String resp = postForm(config.getBaseUrl() + "/apiweb/h5unionpay/unionorder", params, true);
        Map<String, String> result = parseJson(resp);

        String retcode = result.get("retcode");
        // 通联成功码 0000 / 000000
        if (!"0000".equals(retcode) && !"000000".equals(retcode)) {
            throw new BusinessException("通联下单失败：" + result.getOrDefault("retmsg", "未知错误"));
        }
        String payurl = result.get("payurl");
        if (payurl == null || payurl.isEmpty()) {
            throw new BusinessException("通联未返回收银台地址：" + resp);
        }
        log.info("通联下单成功: reqsn={}, trxid={}, payurl={}", reqsn, result.get("trxid"), payurl);
        return payurl;
    }

    /**
     * 交易查询
     *
     * @return 通联响应（含 trxstatus/trxid 等），已验签
     */
    public Map<String, String> queryTransaction(String cusid, String reqsn) {
        Map<String, String> params = new TreeMap<>();
        params.put("cusid", cusid);
        params.put("appid", config.getAppId());
        params.put("reqsn", reqsn);
        params.put("randomstr", randomStr());
        params.put("signtype", "RSA");
        params.put("sign", sign(params));

        String resp = postForm(config.getBaseUrl() + "/apiweb/tranx/query", params, false);
        Map<String, String> result = parseJson(resp);
        if (!verify(result)) {
            throw new BusinessException("通联交易查询响应验签失败");
        }
        log.info("通联交易查询: reqsn={}, trxstatus={}, trxid={}", reqsn, result.get("trxstatus"), result.get("trxid"));
        return result;
    }

    /**
     * 交易退款
     *
     * @param cusid    子商户号
     * @param reqsn    退款单号
     * @param trxamt   退款金额（分）
     * @param oldreqsn 原商户订单号
     */
    public Map<String, String> refund(String cusid, String reqsn, long trxamt, String oldreqsn) {
        Map<String, String> params = new TreeMap<>();
        params.put("cusid", cusid);
        params.put("appid", config.getAppId());
        params.put("reqsn", reqsn);
        params.put("trxamt", String.valueOf(trxamt));
        params.put("oldreqsn", oldreqsn);
        params.put("randomstr", randomStr());
        params.put("signtype", "RSA");
        params.put("sign", sign(params));

        String resp = postForm(config.getBaseUrl() + "/apiweb/tranx/refund", params, false);
        Map<String, String> result = parseJson(resp);
        if (!verify(result)) {
            throw new BusinessException("通联退款响应验签失败");
        }
        String retcode = result.get("retcode");
        if (!"0000".equals(retcode) && !"000000".equals(retcode)) {
            throw new BusinessException("通联退款失败：" + result.getOrDefault("retmsg", "未知错误"));
        }
        log.info("通联退款成功: reqsn={}, oldreqsn={}, trxid={}", reqsn, oldreqsn, result.get("trxid"));
        return result;
    }

    // ==================== 工具 ====================

    private String randomStr() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 分转元（用于 asinfo 分账金额，通联规范 type=01 时金额单位为元）
     * 2200 分 -> "22.00"
     */
    private String amountFenToYuan(long fen) {
        return BigDecimal.valueOf(fen)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /**
     * POST application/x-www-form-urlencoded
     *
     * @param allowRedirect 下单接口可能 302 重定向到收银台
     */
    private String postForm(String url, Map<String, String> params, boolean allowRedirect) {
        String form = params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            String body = response.body();
            // 收银台下单可能返回 302 重定向，取 Location
            if (allowRedirect && (code == 301 || code == 302 || code == 303 || code == 307)) {
                String location = response.headers().firstValue("Location").orElse(null);
                log.info("通联返回重定向: url={}, location={}", url, location);
                if (location != null && !location.isEmpty()) {
                    return "{\"retcode\":\"0000\",\"payurl\":\"" + location + "\"}";
                }
            }
            log.info("通联响应: url={}, status={}, body={}", url, code, body);
            return body;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用通联接口异常: url={}, msg={}", url, e.getMessage());
            throw new BusinessException("调用通联接口失败：" + e.getMessage());
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
            log.error("解析通联响应失败: json={}, err={}", json, e.getMessage());
            throw new BusinessException("解析通联响应失败：" + e.getMessage());
        }
    }
}
