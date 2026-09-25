package com.wine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.common.LoginUser;
import com.wine.common.UserContextHolder;
import com.wine.controller.AdminController;
import com.wine.domain.BarInventory;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 差距4：实物整瓶库存管理单元测试
 * 覆盖：列表(按角色隔离)、新增(默认值+酒吧端强制barId)、更新、删除、预警列表
 */
@ExtendWith(MockitoExtension.class)
public class AdminControllerInventoryTest {

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
        // 平台管理员 role=5
        LoginUser user = new LoginUser();
        user.setUserId(9001L);
        user.setRole(5);
        UserContextHolder.set(user);
    }

    @AfterEach
    public void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    public void testListInventory_platformAdmin_withBarId() {
        BarInventory inv = new BarInventory();
        inv.setId(1L);
        inv.setBarId(1001L);
        inv.setWineSkuId(1L);
        inv.setQuantity(50);
        inv.setAlertThreshold(10);
        when(barInventoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(inv));

        var result = adminController.listInventory(1001L);

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals(1001L, result.getData().get(0).getBarId());
    }

    @Test
    public void testListInventory_platformAdmin_noBarId() {
        when(barInventoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var result = adminController.listInventory(null);

        assertEquals(200, result.getCode());
        assertTrue(result.getData().isEmpty());
    }

    @Test
    public void testCreateInventory_withDefaults() {
        BarInventory inv = new BarInventory();
        inv.setBarId(1001L);
        inv.setWineSkuId(1L);
        // 不设置 quantity/status/alertThreshold，测试默认值

        when(barInventoryMapper.insert(any(BarInventory.class))).thenAnswer(invocation -> {
            BarInventory arg = invocation.getArgument(0);
            arg.setId(123L);
            return 1;
        });

        var result = adminController.createInventory(inv);

        assertEquals(200, result.getCode());
        assertEquals(123L, result.getData().getId());
        assertEquals(0, result.getData().getQuantity());
        assertEquals(1, result.getData().getStatus());
        assertEquals(0, result.getData().getAlertThreshold());
        verify(barInventoryMapper).insert(inv);
    }

    @Test
    public void testCreateInventory_barAdmin_forcedBarId() {
        // 切换为酒吧管理员 role=2, barId=1001
        LoginUser barUser = new LoginUser();
        barUser.setUserId(9002L);
        barUser.setRole(2);
        barUser.setBarId(1001L);
        UserContextHolder.set(barUser);

        BarInventory inv = new BarInventory();
        inv.setWineSkuId(1L);
        inv.setQuantity(20);
        inv.setBarId(9999L); // 传入错误的barId

        when(barInventoryMapper.insert(any(BarInventory.class))).thenReturn(1);

        adminController.createInventory(inv);

        // 酒吧端强制覆盖为用户所属酒吧
        assertEquals(1001L, inv.getBarId());
    }

    @Test
    public void testUpdateInventory() {
        BarInventory inv = new BarInventory();
        inv.setQuantity(30);
        inv.setAlertThreshold(5);

        when(barInventoryMapper.updateById(any(BarInventory.class))).thenReturn(1);

        var result = adminController.updateInventory(1L, inv);

        assertEquals(200, result.getCode());
        assertEquals(1L, inv.getId());
        verify(barInventoryMapper).updateById(inv);
    }

    @Test
    public void testDeleteInventory() {
        when(barInventoryMapper.deleteById(1L)).thenReturn(1);

        var result = adminController.deleteInventory(1L);

        assertEquals(200, result.getCode());
        verify(barInventoryMapper).deleteById(1L);
    }

    @Test
    public void testAlertInventory_returnsLowStockItems() {
        BarInventory lowStock = new BarInventory();
        lowStock.setId(1L);
        lowStock.setBarId(1001L);
        lowStock.setQuantity(5);
        lowStock.setAlertThreshold(10); // 5 <= 10 → 预警
        when(barInventoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(lowStock));

        var result = adminController.alertInventory(1001L);

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals(5, result.getData().get(0).getQuantity());
    }

    @Test
    public void testAlertInventory_noAlerts() {
        when(barInventoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var result = adminController.alertInventory(1001L);

        assertEquals(200, result.getCode());
        assertTrue(result.getData().isEmpty());
    }
}
