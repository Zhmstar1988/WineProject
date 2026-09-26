# 百万级订单压测 — Bug 与性能瓶颈复盘报告

> 复盘日期：2026-09-26
> 压测规模：1,000,000 订单 / 300 并发
> 最终结果：成功率 100%、TPS 480、库存差异 0ml

---

## 一、背景与目标

本次压测旨在验证葡萄酒自动售酒系统在 10 万用户、百万级订单场景下的：
- 支付 → 出酒 → 库存扣减全流程一致性
- 酒吧（role=2）与酒商（role=6）对账准确性
- 高并发下的系统稳定性

压测过程中先后遇到 **3 个 Bug** 和 **1 个性能优化点**，本文逐一复盘根因、诊断过程、修复方案与验证结果。

---

## 二、Bug 1：日志输出溢出导致后端进程被终止

### 2.1 现象描述

压测执行到约 **180,000 单** 时，后端进程突然终止，压测脚本成功数不再增长。检查发现：
- 无 JVM 崩溃日志（`hs_err_pid*.log`）
- 无 Heap Dump（排除 OOM）
- 系统剩余内存 3.5GB+（排除系统内存不足）
- 进程退出码非 0

### 2.2 根因分析

**直接原因**：TRAE 运行时对命令输出文件设置了 **5GB 上限**，后端日志输出量超限被强制终止。

**深层原因**：开发环境配置存在双重日志放大：
1. MyBatis 使用 `StdOutImpl`，每条 SQL 语句（含参数、结果集）全部输出到 stdout
2. `com.wine` 包日志级别为 `debug`，每个请求输出数十行业务日志

百万级订单 × 每单约 10 条 SQL × 每条 SQL 约 500 字节 = 约 5GB 日志量，正好在 18 万单左右触顶。

### 2.3 诊断过程

| 步骤 | 操作 | 结论 |
|------|------|------|
| 1 | 检查 `hs_err_pid*.log` / `*.hprof` | 不存在，排除 JVM 崩溃/OOM |
| 2 | 检查系统内存 | 3.5GB 空闲，排除系统级 OOM |
| 3 | 检查后端终端输出 | 日志文件大小快速增长 |
| 4 | 查看 TRAE 命令状态 | `Command killed: output file exceeded 5GB limit` |

### 2.4 修复方案

**文件**：[application.yml](file:///g:/WorkSpace/WineProject/wine-backend/src/main/resources/application.yml)

```yaml
# 修复前
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl

logging:
  level:
    com.wine: debug

# 修复后
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.nologging.NoLoggingImpl

logging:
  level:
    com.wine: ${LOG_LEVEL:warn}
    org.springframework.security: warn
    org.springframework: warn
    com.zaxxer.hikari: warn
```

同步修改 [application-dev.yml](file:///g:/WorkSpace/WineProject/wine-backend/src/main/resources/application-dev.yml)。

### 2.5 验证结果

| 指标 | 修复前 | 修复后 |
|------|--------|--------|
| 进程终止节点 | ~180,000 单 | 1,000,000 单正常完成 |
| 日志输出量 | >5GB（超限被杀） | <100MB |
| TPS | 中断 | 480.04 |

### 2.6 经验教训

- **开发环境配置不能直接用于压测**：`StdOutImpl` + `debug` 级别仅适用于本地调试
- **日志量必须可控**：高并发场景下，每行日志乘以请求量后会被指数级放大
- **建议**：压测环境统一使用 `warn` 级别，SQL 日志通过慢查询日志按需采集

---

## 三、Bug 2：data.sql 测试订单未扣减容量导致库存差异 450ml

### 3.1 现象描述

压测完成后，库存一致性核对发现：

| 出酒机 | 瓶位 | 订单出酒量(ml) | 实际消耗(ml) | 差异(ml) |
|--------|------|----------------|--------------|----------|
| 3001 | 1 | 101,700 | 101,500 | **+200** |
| 3001 | 2 | 102,900 | 102,700 | **+200** |
| 3001 | 3 | 101,050 | 101,000 | **+50** |
| 3002 | 1 | 99,900 | 99,900 | 0 |
| 3002 | 2 | 98,400 | 98,400 | 0 |
| **合计** | | | | **450** |

差异规律：仅 3001 的 3 个瓶位有差异，且都是 **订单出酒量 > 实际消耗**。

### 3.2 根因分析

**对账逻辑**：
```sql
order_ml = SUM(volume_ml) FROM order_main WHERE dispenser_id=? AND slot_no=?
consumed_ml = initial_capacity - current_capacity
diff = order_ml - consumed_ml
```

`diff > 0` 说明：数据库中存在订单记录了出酒量，但瓶位容量没有被扣减。

**定位过程**：
1. 查询非 COMPLETED 订单，发现 3 条 `TEST2026092400x` 开头的遗留订单
2. 这些订单来自 [data.sql](file:///g:/WorkSpace/WineProject/wine-backend/src/main/resources/db/data.sql) 初始化脚本
3. 脚本插入了 5 条测试订单（覆盖正常闭环、漏单、盗刷、损耗、授权不一致等场景），但 **没有同步扣减 dispenser_slot 的 current_capacity**
4. 每次压测前执行 `TRUNCATE order_main` + `UPDATE dispenser_slot SET current_capacity=200000000`，但后端启动时 `spring.sql.init.mode=always` 会重新执行 data.sql，导致订单重新插入而容量已被重置

**差异计算验证**：
- 3001:1: TEST001(50ml) + TEST004(150ml) = 200ml ✅
- 3001:2: TEST002(150ml) + TEST005(50ml) = 200ml ✅
- 3001:3: TEST003(50ml) = 50ml ✅
- 合计：450ml ✅

### 3.3 修复方案

**文件**：[data.sql](file:///g:/WorkSpace/WineProject/wine-backend/src/main/resources/db/data.sql#L85-L91)

在测试订单插入语句末尾，追加对应的容量扣减：

```sql
-- 测试订单对应容量扣减（保证库存一致性）
-- 3001:1: TEST001(50ml) + TEST004(150ml) = 200ml
UPDATE dispenser_slot SET current_capacity = current_capacity - 200 WHERE dispenser_id = 3001 AND slot_no = 1;
-- 3001:2: TEST002(150ml) + TEST005(50ml) = 200ml
UPDATE dispenser_slot SET current_capacity = current_capacity - 200 WHERE dispenser_id = 3001 AND slot_no = 2;
-- 3001:3: TEST003(50ml) = 50ml
UPDATE dispenser_slot SET current_capacity = current_capacity - 50 WHERE dispenser_id = 3001 AND slot_no = 3;
```

### 3.4 验证结果

5,000 单小规模回归测试：

| 出酒机 | 瓶位 | 订单出酒量(ml) | 实际消耗(ml) | 差异(ml) |
|--------|------|----------------|--------------|----------|
| 3001 | 1 | 98,300 | 98,300 | **0** |
| 3001 | 2 | 100,100 | 100,100 | **0** |
| 3001 | 3 | 98,950 | 98,950 | **0** |
| 3002 | 1 | 101,000 | 101,000 | 0 |
| 3002 | 2 | 100,000 | 100,000 | 0 |
| **合计** | | | | **0** ✅ |

### 3.5 经验教训

- **初始化数据必须自洽**：测试订单与库存数据必须保持一致，否则会污染对账结果
- **压测前数据清理要彻底**：`TRUNCATE` 后需确认 `data.sql` 不会重新插入干扰数据
- **对账脚本应排除测试数据**：可增加 `WHERE order_no NOT LIKE 'TEST%'` 过滤

---

## 四、Bug 3：数据库/Redis 连接池配置不足

### 4.1 现象描述

虽然连接池问题未直接导致压测失败，但在排查 18 万单崩溃过程中发现：
- HikariCP `maximum-pool-size=100`，而压测并发为 300
- Redis Lettuce `max-active=50`
- Tomcat 默认线程数 200
- MySQL `max_connections=151`

在高并发下存在连接获取超时的潜在风险。

### 4.2 根因分析

下单流程涉及多次数据库交互（查询用户、酒单、瓶位、扣减容量、插入订单）和 Redis 操作（幂等缓存、分布式锁）。300 并发 × 每请求约 3-5 个数据库操作，100 个连接在峰值时可能不足。

### 4.3 修复方案

**文件**：[application.yml](file:///g:/WorkSpace/WineProject/wine-backend/src/main/resources/application.yml)

| 配置项 | 修复前 | 修复后 |
|--------|--------|--------|
| HikariCP maximum-pool-size | 100 | **200** |
| HikariCP minimum-idle | 10 | **20** |
| HikariCP leak-detection-threshold | 无 | **60000**（60s 泄漏检测） |
| Redis Lettuce max-active | 50 | **100** |
| Redis Lettuce max-idle | 20 | **50** |
| Redis Lettuce min-idle | 0 | **5** |
| Tomcat threads.max | 默认 200 | **400** |
| Tomcat threads.min-spare | 默认 10 | **50** |
| Tomcat max-connections | 默认 8192 | **10000** |
| Tomcat accept-count | 默认 100 | **200** |
| MySQL max_connections | 151 | **500**（`SET GLOBAL`） |

### 4.4 验证结果

百万单压测（300 并发）：
- 成功率：100%
- 无连接超时错误
- 下单 P99 延迟：115ms（优化前 375ms，降幅 69%）

### 4.5 经验教训

- **连接池大小需匹配并发量**：通常 `pool_size = (core_count * 2) + effective_spindle_count`，但实际应以压测为准
- **MySQL 端也要调**：应用层连接池不能超过数据库 `max_connections`
- **开启泄漏检测**：`leak-detection-threshold` 可提前发现连接未释放问题

---

## 五、性能优化：幂等缓存 TTL 过长

### 5.1 问题描述

[OrderService.java](file:///g:/WorkSpace/WineProject/wine-backend/src/main/java/com/wine/service/OrderService.java#L82) 中幂等缓存 TTL 原为 **24 小时**。百万级订单意味着 Redis 中会积累大量 `wine:idem:order:*` key，虽然单 key 体积小，但总量大时会增加 Redis 内存压力和 key 扫描成本。

### 5.2 修复方案

```java
// 修复前
private static final long IDEM_TTL_HOURS = 24;
stringRedisTemplate.opsForValue().set(cacheKey, json, IDEM_TTL_HOURS, TimeUnit.HOURS);

// 修复后
private static final long IDEM_TTL_MINUTES = 10;
stringRedisTemplate.opsForValue().set(cacheKey, json, IDEM_TTL_MINUTES, TimeUnit.MINUTES);
```

**依据**：订单从创建到出酒完成通常在 5 分钟内，10 分钟 TTL 足以覆盖重复请求窗口。

### 5.3 验证结果

- 百万单压测中 Redis 内存占用稳定在 ~100MB
- 无幂等失效导致的重复下单

---

## 六、根因汇总与修复清单

| 编号 | 类型 | 严重程度 | 根因 | 修复文件 | 验证状态 |
|------|------|----------|------|----------|----------|
| Bug-1 | 稳定性 | **P0** | MyBatis StdOutImpl + debug 日志导致输出超限 | application.yml, application-dev.yml | ✅ 百万单通过 |
| Bug-2 | 数据一致性 | **P1** | data.sql 测试订单未扣减容量 | data.sql | ✅ 5000 单差异=0 |
| Bug-3 | 性能/容量 | **P2** | 连接池配置不足 | application.yml + MySQL | ✅ 100% 成功率 |
| Opt-1 | 性能优化 | P3 | 幂等缓存 TTL 24h 过长 | OrderService.java | ✅ Redis 内存稳定 |

---

## 七、跨问题共性反思

### 7.1 诊断方法论

本次排查中，"无崩溃日志 + 无 OOM" 的进程终止一度造成困惑。最终通过 **检查运行时输出限制** 才定位到根因。**启示**：进程异常退出时，排查顺序应为：
1. 进程退出码 / 信号
2. 运行时环境限制（输出、文件句柄、内存 cgroup）
3. JVM 层面（OOM、崩溃日志）
4. 应用代码层面

### 7.2 配置管理

开发环境与压测环境的配置差异是 Bug-1 和 Bug-3 的共同根源。**建议**：
- 建立 `application-stress.yml` 独立配置文件
- 压测前通过配置中心或环境变量覆盖开发配置
- 将日志级别、连接池大小等纳入压测 checklist

### 7.3 测试数据自洽性

Bug-2 提醒我们：**测试初始化脚本也是代码**，需要像业务代码一样保证数据一致性。建议在 CI 中增加"初始化数据一致性校验"步骤。

---

## 八、后续改进建议

1. **日志框架升级**：引入 Logback 异步 Appender + 滚动策略，避免日志成为性能瓶颈
2. **连接池监控**：接入 Micrometer 暴露 HikariCP/Redis 连接池指标，设置告警阈值
3. **压测环境隔离**：使用独立的 `stress` profile，禁止 `data.sql` 在压测环境执行
4. **对账脚本增强**：排除 `TEST%` 前缀订单，或增加"仅统计压测订单"的时间范围过滤
5. **幂等缓存清理**：增加定时任务清理过期幂等 key，避免 Redis 内存缓慢增长

---

## 九、结论

本次百万级压测共修复 **3 个 Bug**、优化 **1 个性能点**。修复后系统在 300 并发下稳定处理 100 万订单，成功率 100%，TPS 480，库存差异 0ml，酒吧/酒商对账数据完全一致。

核心收获：**压测不仅是验证性能，更是暴露配置缺陷和数据自洽性问题的有效手段**。开发环境的"方便调试"配置在高并发下可能成为致命瓶颈。
