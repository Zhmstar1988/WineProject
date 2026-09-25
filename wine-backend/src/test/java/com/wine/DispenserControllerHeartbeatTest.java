package com.wine;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wine.controller.DispenserController;
import com.wine.domain.Dispenser;
import com.wine.mapper.DispenserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 差距5：分酒机心跳接收端点单元测试
 * 覆盖：正常心跳、不存在设备、携带firmwareVersion、携带status参数
 */
@ExtendWith(MockitoExtension.class)
public class DispenserControllerHeartbeatTest {

    @BeforeAll
    public static void initMybatisPlusTableInfo() {
        // 纯 Mockito 环境下手动初始化 MyBatis-Plus 表信息缓存，
        // 否则 LambdaUpdateWrapper 无法解析 Dispenser 实体字段
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                Dispenser.class);
    }

    @Mock
    private DispenserMapper dispenserMapper;

    @InjectMocks
    private DispenserController dispenserController;

    @Test
    public void testHeartbeat_normal_success() {
        Dispenser d = new Dispenser();
        d.setId(3001L);
        d.setStatus(1);
        when(dispenserMapper.selectById(3001L)).thenReturn(d);
        when(dispenserMapper.update(eq(null), any(LambdaUpdateWrapper.class))).thenReturn(1);

        var result = dispenserController.heartbeat(3001L, new HashMap<>());

        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertTrue(result.getData().containsKey("lastHeartbeat"));
        verify(dispenserMapper).update(eq(null), any(LambdaUpdateWrapper.class));
    }

    @Test
    public void testHeartbeat_deviceNotFound_return404() {
        when(dispenserMapper.selectById(9999L)).thenReturn(null);

        var result = dispenserController.heartbeat(9999L, new HashMap<>());

        assertEquals(404, result.getCode());
        assertEquals("分酒机不存在", result.getMessage());
        verify(dispenserMapper, never()).update(any(), any());
    }

    @Test
    public void testHeartbeat_withFirmwareVersion() {
        Dispenser d = new Dispenser();
        d.setId(3001L);
        when(dispenserMapper.selectById(3001L)).thenReturn(d);
        when(dispenserMapper.update(eq(null), any(LambdaUpdateWrapper.class))).thenReturn(1);

        Map<String, Object> body = new HashMap<>();
        body.put("firmwareVersion", "v2.0.1");

        var result = dispenserController.heartbeat(3001L, body);

        assertEquals(200, result.getCode());
        verify(dispenserMapper).update(eq(null), any(LambdaUpdateWrapper.class));
    }

    @Test
    public void testHeartbeat_withStatusParameter() {
        Dispenser d = new Dispenser();
        d.setId(3001L);
        when(dispenserMapper.selectById(3001L)).thenReturn(d);
        when(dispenserMapper.update(eq(null), any(LambdaUpdateWrapper.class))).thenReturn(1);

        Map<String, Object> body = new HashMap<>();
        body.put("status", 2); // 故障状态

        var result = dispenserController.heartbeat(3001L, body);

        assertEquals(200, result.getCode());
    }

    @Test
    public void testHeartbeat_withInvalidStatus_ignored() {
        Dispenser d = new Dispenser();
        d.setId(3001L);
        when(dispenserMapper.selectById(3001L)).thenReturn(d);
        when(dispenserMapper.update(eq(null), any(LambdaUpdateWrapper.class))).thenReturn(1);

        Map<String, Object> body = new HashMap<>();
        body.put("status", "invalid"); // 非数字

        var result = dispenserController.heartbeat(3001L, body);

        assertEquals(200, result.getCode());
    }

    @Test
    public void testHeartbeat_nullBody_defaultOnline() {
        Dispenser d = new Dispenser();
        d.setId(3001L);
        when(dispenserMapper.selectById(3001L)).thenReturn(d);
        when(dispenserMapper.update(eq(null), any(LambdaUpdateWrapper.class))).thenReturn(1);

        var result = dispenserController.heartbeat(3001L, null);

        assertEquals(200, result.getCode());
    }
}
