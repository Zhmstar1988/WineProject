package com.wine.stress;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 葡萄酒按杯交易压测客户端
 *
 * 模式：
 *   --setup  : 清空交易数据，创建10万C端用户，扩容瓶位
 *   --run    : 执行百万级订单全链路压测（下单→支付→出酒）
 *   --verify : 校验支付-出酒-库存一致性 + 对账
 *   --all    : 依次执行 setup → run → verify
 *
 * 用法: java -cp "..." com.wine.stress.StressTest --all --orders 1000000 --users 100000 --threads 200
 */
public class StressTest {

    private static final String JWT_SECRET = "WineSingaporeDevSecretKeyForJWTTokenGenerationMustBeLong";
    private static final SecretKey JWT_KEY = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
    private static final long JWT_EXPIRY = 604800000L; // 7天

    private static String BASE_URL = "http://127.0.0.1:8080/api";
    private static String DB_URL = "jdbc:mysql://127.0.0.1:3306/winedb?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Singapore&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true";
    private static String DB_USER = "root";
    private static String DB_PASS = "root";

    private static int USERS = 100_000;
    private static int ORDERS = 1_000_000;
    private static int THREADS = 200;

    // 压测使用的瓶位池（200个并发瓶位）
    private static final long[] DISPENSER_IDS = {3001L, 3002L};
    private static final long[] BAR_IDS = {1001L, 1002L};
    private static final long WINE_SKU_ID = 2001L;
    private static final int VOLUME_ML = 50;
    private static final int SLOT_COUNT = 100;

    // 指标统计
    private static final AtomicLong totalRequests = new AtomicLong(0);
    private static final AtomicLong successRequests = new AtomicLong(0);
    private static final AtomicLong failRequests = new AtomicLong(0);
    private static final AtomicLong totalLatencyNs = new AtomicLong(0);
    private static final ConcurrentHashMap<String, AtomicLong> errorCounts = new ConcurrentHashMap<>();
    // 分阶段延迟（纳秒）
    private static final ConcurrentHashMap<String, AtomicLong> stageLatency = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> stageCount = new ConcurrentHashMap<>();

    // 延迟百分位采样
    private static final int LATENCY_SAMPLE_SIZE = 200_000;
    private static final long[] latencySamples = new long[LATENCY_SAMPLE_SIZE];
    private static final AtomicInteger sampleIndex = new AtomicInteger(0);

    private static HikariDataSource dataSource;
    private static HttpClient httpClient;
    private static List<String> userTokens; // 预生成的用户JWT

    public static void main(String[] args) throws Exception {
        parseArgs(args);
        initDataSource();
        initHttpClient();

        String mode = args.length > 0 ? args[0] : "--all";
        switch (mode) {
            case "--setup" -> setup();
            case "--run" -> runStress();
            case "--verify" -> verify();
            case "--reconcile" -> triggerReconcile();
            case "--all" -> { setup(); runStress(); verify(); }
            default -> System.err.println("未知模式: " + mode);
        }

        if (dataSource != null) dataSource.close();
        System.exit(0);
    }

    private static void parseArgs(String[] args) {
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--orders" -> ORDERS = Integer.parseInt(args[++i]);
                case "--users" -> USERS = Integer.parseInt(args[++i]);
                case "--threads" -> THREADS = Integer.parseInt(args[++i]);
                case "--base-url" -> BASE_URL = args[++i];
                case "--db-url" -> DB_URL = args[++i];
            }
        }
    }

    private static void initDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(DB_URL);
        config.setUsername(DB_USER);
        config.setPassword(DB_PASS);
        config.setMaximumPoolSize(50);
        config.setMinimumIdle(10);
        config.setConnectionTimeout(10000);
        dataSource = new HikariDataSource(config);
    }

    private static void initHttpClient() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newFixedThreadPool(THREADS * 2))
                .build();
    }

    // ==================== 数据准备 ====================

    private static void setup() throws Exception {
        System.out.println("========== [SETUP] 压测数据准备 ==========");
        long start = System.currentTimeMillis();

        // 1. 清空交易表
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            String[] truncates = {
                    "TRUNCATE TABLE order_main",
                    "TRUNCATE TABLE dispense_ticket",
                    "TRUNCATE TABLE reconcile_log",
                    "TRUNCATE TABLE loss_audit_log",
                    "TRUNCATE TABLE sms_otp_log",
                    "TRUNCATE TABLE inventory_log",
                    "TRUNCATE TABLE replenish_order",
                    "TRUNCATE TABLE bar_inventory"
            };
            for (String sql : truncates) st.execute(sql);
            System.out.println("  [1/4] 已清空交易与日志表");
        }

        // 2. 删除旧的压测用户
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            int del = st.executeUpdate("DELETE FROM sys_user WHERE role = 1 AND id >= 10000");
            System.out.println("  [2/4] 已删除旧压测用户: " + del);
        }

        // 3. 创建 10万 C端用户（批量插入）
        System.out.println("  [3/4] 开始创建 " + USERS + " 个C端用户...");
        String insertUser = "INSERT INTO sys_user (id, phone, login_type, role, nickname, age_verified, status) VALUES (?,?,?,?,?,?,?)";
        int batchSize = 1000;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(insertUser)) {
            conn.setAutoCommit(false);
            long userIdBase = 100_000L;
            for (int i = 0; i < USERS; i++) {
                long uid = userIdBase + i;
                ps.setLong(1, uid);
                ps.setString(2, "+65" + String.format("%08d", i));
                ps.setInt(3, 1);
                ps.setInt(4, 1);
                ps.setString(5, "用户" + String.format("%08d", i));
                ps.setBoolean(6, true); // 年龄已验证
                ps.setInt(7, 1);
                ps.addBatch();
                if ((i + 1) % batchSize == 0) {
                    ps.executeBatch();
                    conn.commit();
                    if ((i + 1) % 10000 == 0) System.out.println("    已创建 " + (i + 1) + " 用户");
                }
            }
            ps.executeBatch();
            conn.commit();
        }
        System.out.println("  [3/4] 已创建 " + USERS + " 个C端用户 (age_verified=true)");

        // 4. 清空 Redis 缓存（通过 HTTP 无法直接清，调用 actuator 或跳过）
        //    此处不做 Redis 清理，因为 stress profile 的分布式锁会自动过期
        System.out.println("  [4/4] 提示：如需清 Redis 缓存，请执行 redis-cli FLUSHALL");

        long elapsed = (System.currentTimeMillis() - start) / 1000;
        System.out.println("========== [SETUP] 完成，耗时 " + elapsed + "s ==========");
    }

    // ==================== 压测执行 ====================

    private static void runStress() throws Exception {
        System.out.println("========== [RUN] 百万级订单压测 ==========");
        System.out.println("  订单数: " + ORDERS + ", 并发线程: " + THREADS + ", 用户数: " + USERS);

        // 预生成用户 token（只需前 N 个用户覆盖订单）
        int tokenUsers = Math.min(USERS, ORDERS);
        userTokens = new ArrayList<>(tokenUsers);
        long uidBase = 100_000L;
        for (int i = 0; i < tokenUsers; i++) {
            userTokens.add(generateToken(uidBase + i));
        }
        System.out.println("  已预生成 " + tokenUsers + " 个用户 JWT");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch latch = new CountDownLatch(ORDERS);
        Random rand = new Random(42);
        long globalStart = System.nanoTime();

        // 进度打印
        ScheduledExecutorService progressScheduler = Executors.newSingleThreadScheduledExecutor();
        progressScheduler.scheduleAtFixedRate(() -> {
            long done = successRequests.get() + failRequests.get();
            double pct = done * 100.0 / ORDERS;
            long elapsedSec = (System.nanoTime() - globalStart) / 1_000_000_000;
            double rps = elapsedSec > 0 ? done * 1.0 / elapsedSec : 0;
            System.out.printf("  进度: %.1f%% (%d/%d) | RPS: %.0f | 成功: %d | 失败: %d%n",
                    pct, done, ORDERS, rps, successRequests.get(), failRequests.get());
        }, 5, 5, TimeUnit.SECONDS);

        for (int i = 0; i < ORDERS; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    String token = userTokens.get(idx % tokenUsers);
                    // 随机选择瓶位（分散并发）
                    int dispIdx = rand.nextInt(DISPENSER_IDS.length);
                    long dispenserId = DISPENSER_IDS[dispIdx];
                    long barId = BAR_IDS[dispIdx];
                    int slotNo = rand.nextInt(SLOT_COUNT) + 1;
                    executeOrderFlow(token, barId, dispenserId, slotNo);
                } catch (Exception e) {
                    recordError(e.getClass().getSimpleName());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        progressScheduler.shutdownNow();
        pool.shutdownNow();

        long totalElapsed = (System.nanoTime() - globalStart) / 1_000_000_000;
        System.out.println("========== [RUN] 压测完成 ==========");
        System.out.println("  总耗时: " + totalElapsed + "s");
        System.out.println("  总请求数: " + totalRequests.get());
        System.out.println("  成功: " + successRequests.get());
        System.out.println("  失败: " + failRequests.get());
        System.out.println("  整体错误率: " + String.format("%.2f%%", failRequests.get() * 100.0 / Math.max(1, totalRequests.get())));
        double avgRps = totalElapsed > 0 ? totalRequests.get() * 1.0 / totalElapsed : 0;
        System.out.println("  平均 RPS: " + String.format("%.0f", avgRps));
        double avgLatencyMs = totalRequests.get() > 0
                ? (totalLatencyNs.get() / totalRequests.get()) / 1_000_000.0 : 0;
        System.out.println("  平均延迟: " + String.format("%.2f ms", avgLatencyMs));

        // 百分位
        printPercentiles();

        // 错误分布
        if (!errorCounts.isEmpty()) {
            System.out.println("  --- 错误分布 ---");
            errorCounts.forEach((k, v) -> System.out.println("    " + k + ": " + v.get()));
        }

        // 分阶段延迟
        System.out.println("  --- 分阶段平均延迟 ---");
        stageCount.forEach((stage, cnt) -> {
            AtomicLong lat = stageLatency.get(stage);
            if (cnt.get() > 0 && lat != null) {
                System.out.printf("    %-12s: %.2f ms (n=%d)%n", stage,
                        (lat.get() / cnt.get()) / 1_000_000.0, cnt.get());
            }
        });
    }

    /**
     * 执行单笔订单全链路：下单 → 支付 → 支付回调 → 出酒 → 出酒回调
     */
    private static void executeOrderFlow(String token, long barId, long dispenserId, int slotNo) throws Exception {
        long orderStart = System.nanoTime();

        // 1. 创建订单
        long t0 = System.nanoTime();
        String createBody = String.format(
                "{\"barId\":%d,\"dispenserId\":%d,\"slotNo\":%d,\"wineSkuId\":%d,\"volumeMl\":%d}",
                barId, dispenserId, slotNo, WINE_SKU_ID, VOLUME_ML);
        HttpResponse<String> createResp = httpSend("POST", "/order/create", token, createBody);
        recordStage("create", t0);
        if (createResp.statusCode() != 200) {
            recordError("create_" + createResp.statusCode());
            return;
        }
        String orderNo = extractJson(createResp.body(), "orderNo");
        if (orderNo == null) {
            recordError("create_no_orderNo");
            return;
        }

        // 2. 发起支付
        long t1 = System.nanoTime();
        HttpResponse<String> payResp = httpSend("POST", "/payment/pay/" + orderNo, token, "");
        recordStage("pay", t1);
        if (payResp.statusCode() != 200) {
            recordError("pay_" + payResp.statusCode());
            return;
        }

        // 3. 支付回调（模拟通联异步通知）
        long t2 = System.nanoTime();
        String notifyBody = "accessOrderId=" + orderNo + "&resultCode=SUCCESS&amount=18.00&currency=SGD&transId=MOCK" + orderNo;
        HttpRequest notifyReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/payment/notify"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(notifyBody))
                .build();
        HttpResponse<String> notifyResp = httpClient.send(notifyReq, HttpResponse.BodyHandlers.ofString());
        recordStage("notify", t2);
        totalRequests.incrementAndGet();
        if (notifyResp.statusCode() != 200 || !"success".equals(notifyResp.body().trim())) {
            recordError("notify_" + notifyResp.statusCode());
            return;
        }

        // 4. 发起出酒
        long t3 = System.nanoTime();
        HttpResponse<String> dispenseResp = httpSend("POST", "/dispense/start/" + orderNo, token, "");
        recordStage("dispense_start", t3);
        if (dispenseResp.statusCode() != 200) {
            recordError("dispense_" + dispenseResp.statusCode());
            return;
        }

        // 5. 出酒回调（模拟分酒机成功回调）
        long t4 = System.nanoTime();
        String callbackBody = String.format("{\"order_id\":\"%s\",\"status\":\"SUCCESS\",\"actual_ml\":%d}", orderNo, VOLUME_ML);
        HttpRequest cbReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/dispense/callback"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(callbackBody))
                .build();
        HttpResponse<String> cbResp = httpClient.send(cbReq, HttpResponse.BodyHandlers.ofString());
        recordStage("callback", t4);
        totalRequests.incrementAndGet();
        if (cbResp.statusCode() != 200) {
            recordError("callback_" + cbResp.statusCode());
            return;
        }

        // 全链路成功
        successRequests.incrementAndGet();
        long elapsed = System.nanoTime() - orderStart;
        totalLatencyNs.addAndGet(elapsed);
        // 采样延迟
        int idx = sampleIndex.getAndIncrement() % LATENCY_SAMPLE_SIZE;
        latencySamples[idx] = elapsed;
    }

    private static HttpResponse<String> httpSend(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(10));
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.GET();
        }
        HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        totalRequests.incrementAndGet();
        return resp;
    }

    private static String extractJson(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private static void recordStage(String stage, long startNs) {
        long lat = System.nanoTime() - startNs;
        stageLatency.computeIfAbsent(stage, k -> new AtomicLong(0)).addAndGet(lat);
        stageCount.computeIfAbsent(stage, k -> new AtomicLong(0)).incrementAndGet();
    }

    private static void recordError(String type) {
        failRequests.incrementAndGet();
        errorCounts.computeIfAbsent(type, k -> new AtomicLong(0)).incrementAndGet();
    }

    private static void printPercentiles() {
        int count = Math.min(sampleIndex.get(), LATENCY_SAMPLE_SIZE);
        if (count == 0) return;
        long[] sorted = new long[count];
        System.arraycopy(latencySamples, 0, sorted, 0, count);
        java.util.Arrays.sort(sorted);
        double tp50 = sorted[count / 2] / 1_000_000.0;
        double tp90 = sorted[(int) (count * 0.9)] / 1_000_000.0;
        double tp99 = sorted[(int) (count * 0.99)] / 1_000_000.0;
        double tp999 = sorted[(int) (count * 0.999)] / 1_000_000.0;
        System.out.printf("  延迟百分位: TP50=%.1fms TP90=%.1fms TP99=%.1fms TP99.9=%.1fms%n", tp50, tp90, tp99, tp999);
    }

    private static String generateToken(long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", 1)
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + JWT_EXPIRY))
                .signWith(JWT_KEY)
                .compact();
    }

    // ==================== 一致性校验 ====================

    private static void verify() throws Exception {
        System.out.println("========== [VERIFY] 支付-出酒-库存一致性校验 ==========");

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            // 1. 订单总数
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM order_main");
            rs.next();
            long orderCount = rs.getLong(1);
            System.out.println("  订单总数: " + orderCount);

            // 2. 订单状态分布
            System.out.println("  --- 订单状态分布 (1待支付 2支付中 3已付款 4已完成 5已退款) ---");
            rs = st.executeQuery("SELECT status, COUNT(*) FROM order_main GROUP BY status ORDER BY status");
            while (rs.next()) {
                System.out.println("    status=" + rs.getInt(1) + ": " + rs.getLong(2));
            }

            // 3. 履约单状态分布
            System.out.println("  --- 履约单状态分布 (1就绪 2出酒中 3成功 4失败) ---");
            rs = st.executeQuery("SELECT status, COUNT(*) FROM dispense_ticket GROUP BY status ORDER BY status");
            while (rs.next()) {
                System.out.println("    status=" + rs.getInt(1) + ": " + rs.getLong(2));
            }

            // 4. 核心一致性：已完成订单数 vs 成功出酒履约单数
            rs = st.executeQuery(
                    "SELECT " +
                    "(SELECT COUNT(*) FROM order_main WHERE status = 4) AS completed_orders, " +
                    "(SELECT COUNT(*) FROM dispense_ticket WHERE status = 3) AS success_tickets, " +
                    "(SELECT COUNT(*) FROM order_main WHERE pay_status = 1) AS paid_orders");
            rs.next();
            long completed = rs.getLong("completed_orders");
            long successTickets = rs.getLong("success_tickets");
            long paidOrders = rs.getLong("paid_orders");
            System.out.println("  已完成订单(status=4): " + completed);
            System.out.println("  成功出酒履约单(status=3): " + successTickets);
            System.out.println("  已支付订单(pay_status=1): " + paidOrders);
            boolean payDispenseMatch = completed == successTickets;
            System.out.println("  支付-出酒一致性: " + (payDispenseMatch ? "✅ 通过" : "❌ 不匹配"));

            // 5. 库存一致性：订单总出酒量 = 瓶位总扣减量
            rs = st.executeQuery(
                    "SELECT " +
                    "(SELECT COALESCE(SUM(volume_ml),0) FROM order_main WHERE status = 4) AS order_volume, " +
                    "(SELECT COALESCE(SUM(initial_capacity - current_capacity),0) FROM dispenser_slot) AS slot_deducted");
            rs.next();
            long orderVolume = rs.getLong("order_volume");
            long slotDeducted = rs.getLong("slot_deducted");
            System.out.println("  已完成订单总出酒量: " + orderVolume + " ml");
            System.out.println("  瓶位总扣减量: " + slotDeducted + " ml");
            boolean invMatch = orderVolume == slotDeducted;
            System.out.println("  出酒-库存一致性: " + (invMatch ? "✅ 通过" : "❌ 不匹配 (差额: " + (orderVolume - slotDeducted) + " ml)"));

            // 6. 支付金额一致性
            rs = st.executeQuery(
                    "SELECT COALESCE(SUM(paid_amount),0) FROM order_main WHERE pay_status = 1");
            rs.next();
            System.out.println("  已支付订单总金额: SGD " + rs.getBigDecimal(1));

            // 7. 孤儿订单（已支付但无履约单）
            rs = st.executeQuery(
                    "SELECT COUNT(*) FROM order_main o WHERE o.pay_status = 1 " +
                    "AND NOT EXISTS (SELECT 1 FROM dispense_ticket t WHERE t.order_no = o.order_no)");
            rs.next();
            long orphanPaid = rs.getLong(1);
            System.out.println("  已支付但无履约单的孤儿订单: " + orphanPaid + (orphanPaid == 0 ? " ✅" : " ❌"));

            // 8. 孤儿履约单（有履约单但订单未支付）
            rs = st.executeQuery(
                    "SELECT COUNT(*) FROM dispense_ticket t " +
                    "JOIN order_main o ON t.order_no = o.order_no WHERE o.pay_status = 0");
            rs.next();
            long orphanTicket = rs.getLong(1);
            System.out.println("  未支付但有履约单的异常: " + orphanTicket + (orphanTicket == 0 ? " ✅" : " ❌"));
        }

        // 9. 触发对账跑批并查询结果
        System.out.println("  --- 触发三向核对跑批 ---");
        triggerReconcile();

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT reconcile_result, COUNT(*) FROM reconcile_log GROUP BY reconcile_result ORDER BY reconcile_result");
            System.out.println("  --- 对账结果分布 (1一致 2漏单 3盗刷 4损耗 5授权不一致) ---");
            while (rs.next()) {
                System.out.println("    result=" + rs.getInt(1) + ": " + rs.getLong(2));
            }
        }
        System.out.println("========== [VERIFY] 校验完成 ==========");
    }

    private static void triggerReconcile() throws Exception {
        // 使用平台管理员 token (id=9001, role=5)
        String adminToken = Jwts.builder()
                .subject("9001")
                .claim("role", 5)
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + JWT_EXPIRY))
                .signWith(JWT_KEY)
                .compact();
        HttpResponse<String> resp = httpSend("POST", "/admin/reconcile/run?date=" + java.time.LocalDate.now(), adminToken, "");
        System.out.println("  对账跑批触发: HTTP " + resp.statusCode());
    }
}
