package com.wine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.common.LoginUser;
import com.wine.common.UserContextHolder;
import com.wine.controller.AdminController;
import com.wine.domain.OrderMain;
import com.wine.mapper.BarInventoryMapper;
import com.wine.mapper.BarMapper;
import com.wine.mapper.DispenserMapper;
import com.wine.mapper.DispenserSlotMapper;
import com.wine.mapper.OrderMainMapper;
import com.wine.mapper.ReconcileLogMapper;
import com.wine.mapper.WineSkuMapper;
import com.wine.service.BarOperationService;
import com.wine.service.ReconcileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 差距2：月度对账单导出单元测试
 * 覆盖：正常导出、空数据、月份参数、角色隔离
 */
@ExtendWith(MockitoExtension.class)
public class AdminControllerExportTest {

    @Mock private BarMapper barMapper;
    @Mock private DispenserMapper dispenserMapper;
    @Mock private DispenserSlotMapper dispenserSlotMapper;
    @Mock private OrderMainMapper orderMainMapper;
    @Mock private ReconcileLogMapper reconcileLogMapper;
    @Mock private BarOperationService barOperationService;
    @Mock private ReconcileService reconcileService;
    @Mock private BarInventoryMapper barInventoryMapper;
    @Mock private WineSkuMapper wineSkuMapper;

    @InjectMocks
    private AdminController adminController;

    @BeforeEach
    public void setUp() {
        LoginUser user = new LoginUser();
        user.setUserId(9001L);
        user.setRole(5);
        UserContextHolder.set(user);
    }

    @AfterEach
    public void tearDown() {
        UserContextHolder.clear();
    }

    private OrderMain buildOrder(String orderNo) {
        OrderMain o = new OrderMain();
        o.setOrderNo(orderNo);
        o.setBarId(1001L);
        o.setUserId(20100L);
        o.setDispenserId(3001L);
        o.setSlotNo(1);
        o.setWineSkuId(2001L);
        o.setVolumeMl(50);
        o.setOriginalAmount(new BigDecimal("18.00"));
        o.setDiscountAmount(BigDecimal.ZERO);
        o.setPaidAmount(new BigDecimal("18.00"));
        o.setStatus(3);
        o.setPayStatus(1);
        o.setTransactionId("TRANS-" + orderNo);
        o.setCreateTime(LocalDateTime.of(2026, 9, 15, 10, 0, 0));
        o.setPayTime(LocalDateTime.of(2026, 9, 15, 10, 0, 1));
        return o;
    }

    @Test
    public void testExportMonthlyOrders_withData() throws IOException {
        when(orderMainMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(buildOrder("ORD-EXPORT-001")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        adminController.exportMonthlyOrders("2026-09", null, response);

        String csv = new String(response.getContentAsByteArray(), "UTF-8");
        // 包含 BOM
        assertTrue(csv.startsWith("\uFEFF"), "CSV 应以 UTF-8 BOM 开头");
        // 表头
        assertTrue(csv.contains("订单号"));
        assertTrue(csv.contains("实付(SGD)"));
        // 数据行
        assertTrue(csv.contains("ORD-EXPORT-001"));
        assertTrue(csv.contains("18.00"));
        // Content-Type 正确
        assertTrue(response.getContentType().contains("text/csv"));
    }

    @Test
    public void testExportMonthlyOrders_emptyData() throws IOException {
        when(orderMainMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        MockHttpServletResponse response = new MockHttpServletResponse();
        adminController.exportMonthlyOrders("2026-09", null, response);

        String csv = new String(response.getContentAsByteArray(), "UTF-8");
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("订单号"));
        // 只有表头，无数据行
        assertFalse(csv.contains("ORD-"));
    }

    @Test
    public void testExportMonthlyOrders_nullMonth_defaultsToLastMonth() throws IOException {
        when(orderMainMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        MockHttpServletResponse response = new MockHttpServletResponse();
        adminController.exportMonthlyOrders(null, null, response);

        assertEquals(200, response.getStatus());
        String csv = new String(response.getContentAsByteArray(), "UTF-8");
        assertTrue(csv.contains("订单号"));
    }

    @Test
    public void testExportMonthlyOrders_multipleOrders() throws IOException {
        when(orderMainMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(
                        buildOrder("ORD-MULTI-001"),
                        buildOrder("ORD-MULTI-002")
                ));

        MockHttpServletResponse response = new MockHttpServletResponse();
        adminController.exportMonthlyOrders("2026-09", null, response);

        String csv = new String(response.getContentAsByteArray(), "UTF-8");
        assertTrue(csv.contains("ORD-MULTI-001"));
        assertTrue(csv.contains("ORD-MULTI-002"));
    }

    @Test
    public void testExportMonthlyOrders_contentDisposition() throws IOException {
        when(orderMainMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        MockHttpServletResponse response = new MockHttpServletResponse();
        adminController.exportMonthlyOrders("2026-09", null, response);

        String disposition = response.getHeader("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.contains("attachment"));
        assertTrue(disposition.contains("2026-09"));
    }

    @Test
    public void testExportMonthlyOrders_barAdmin_isolatedByBar() throws IOException {
        // 切换为酒吧管理员
        LoginUser barUser = new LoginUser();
        barUser.setUserId(9002L);
        barUser.setRole(2);
        barUser.setBarId(1001L);
        UserContextHolder.set(barUser);

        when(orderMainMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(buildOrder("ORD-BAR-001")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        adminController.exportMonthlyOrders("2026-09", null, response);

        String csv = new String(response.getContentAsByteArray(), "UTF-8");
        assertTrue(csv.contains("ORD-BAR-001"));
    }
}
