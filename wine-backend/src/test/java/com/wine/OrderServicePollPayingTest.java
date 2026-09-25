package com.wine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.client.AllinpayIntlClient;
import com.wine.domain.DispenseTicket;
import com.wine.domain.OrderMain;
import com.wine.enums.OrderStatusEnum;
import com.wine.enums.PayStatusEnum;
import com.wine.mapper.DispenseTicketMapper;
import com.wine.mapper.OrderMainMapper;
import com.wine.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 差距1：PAYING 状态兜底轮询单元测试
 * 覆盖：支付成功转PAID+生成履约单、未支付回退PENDING、查询异常跳过、空列表直接返回
 */
@ExtendWith(MockitoExtension.class)
public class OrderServicePollPayingTest {

    @Mock
    private OrderMainMapper orderMainMapper;

    @Mock
    private AllinpayIntlClient allinpayIntlClient;

    @Mock
    private DispenseTicketMapper dispenseTicketMapper;

    @InjectMocks
    private OrderService orderService;

    @BeforeEach
    public void setUp() {
        ReflectionTestUtils.setField(orderService, "payingPollMinutes", 10);
    }

    private OrderMain buildPayingOrder(String orderNo) {
        OrderMain o = new OrderMain();
        o.setId(1L);
        o.setOrderNo(orderNo);
        o.setUserId(100L);
        o.setBarId(200L);
        o.setDispenserId(300L);
        o.setSlotNo(1);
        o.setWineSkuId(400L);
        o.setVolumeMl(50);
        o.setPaidAmount(new BigDecimal("18.00"));
        o.setStatus(OrderStatusEnum.PAYING.getCode());
        o.setPayStatus(PayStatusEnum.UNPAID.getCode());
        o.setCreateTime(LocalDateTime.now().minusMinutes(20));
        return o;
    }

    @Test
    public void testPollPayingOrders_emptyList_returnZero() {
        when(orderMainMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        int count = orderService.pollPayingOrders();

        assertEquals(0, count);
        verify(allinpayIntlClient, never()).queryOrder(anyString());
        verify(dispenseTicketMapper, never()).insert(any());
    }

    @Test
    public void testPollPayingOrders_paymentSuccess_updateToPaidAndCreateTicket() {
        OrderMain order = buildPayingOrder("ORD-SUCCESS-001");
        when(orderMainMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(order));

        Map<String, String> queryResult = new HashMap<>();
        queryResult.put("resultCode", "SUCCESS");
        queryResult.put("transId", "TRANS-123");
        when(allinpayIntlClient.queryOrder("ORD-SUCCESS-001")).thenReturn(queryResult);

        int count = orderService.pollPayingOrders();

        assertEquals(1, count);
        // 订单状态更新为 PAID
        assertEquals(OrderStatusEnum.PAID.getCode(), order.getStatus());
        assertEquals(PayStatusEnum.SUCCESS.getCode(), order.getPayStatus());
        assertEquals("TRANS-123", order.getTransactionId());
        assertNotNull(order.getPayTime());
        verify(orderMainMapper).updateById(order);

        // 生成履约单
        ArgumentCaptor<DispenseTicket> ticketCaptor = ArgumentCaptor.forClass(DispenseTicket.class);
        verify(dispenseTicketMapper).insert(ticketCaptor.capture());
        DispenseTicket ticket = ticketCaptor.getValue();
        assertEquals("ORD-SUCCESS-001", ticket.getOrderNo());
        assertEquals(1L, ticket.getOrderId());
        assertEquals(100L, ticket.getUserId());
        assertEquals(300L, ticket.getDispenserId());
        assertEquals(1, ticket.getSlotNo());
        assertEquals(50, ticket.getTargetMl());
        assertFalse(ticket.getCupPresent());
    }

    @Test
    public void testPollPayingOrders_paymentSuccess_withCode0000() {
        OrderMain order = buildPayingOrder("ORD-0000-001");
        when(orderMainMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(order));

        Map<String, String> queryResult = new HashMap<>();
        queryResult.put("resultCode", "0000");
        when(allinpayIntlClient.queryOrder("ORD-0000-001")).thenReturn(queryResult);

        orderService.pollPayingOrders();

        assertEquals(OrderStatusEnum.PAID.getCode(), order.getStatus());
        verify(dispenseTicketMapper).insert(any(DispenseTicket.class));
    }

    @Test
    public void testPollPayingOrders_paymentFailed_fallbackToPending() {
        OrderMain order = buildPayingOrder("ORD-FAIL-001");
        when(orderMainMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(order));

        Map<String, String> queryResult = new HashMap<>();
        queryResult.put("resultCode", "PENDING");
        when(allinpayIntlClient.queryOrder("ORD-FAIL-001")).thenReturn(queryResult);

        int count = orderService.pollPayingOrders();

        assertEquals(1, count);
        assertEquals(OrderStatusEnum.PENDING.getCode(), order.getStatus());
        assertEquals(PayStatusEnum.FAILED.getCode(), order.getPayStatus());
        assertTrue(order.getRemark().contains("回退待支付"));
        verify(orderMainMapper).updateById(order);
        verify(dispenseTicketMapper, never()).insert(any());
    }

    @Test
    public void testPollPayingOrders_queryException_skipAndContinue() {
        OrderMain order1 = buildPayingOrder("ORD-EX-001");
        OrderMain order2 = buildPayingOrder("ORD-OK-002");
        when(orderMainMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(order1, order2));

        // 第一个订单查询抛异常
        when(allinpayIntlClient.queryOrder("ORD-EX-001"))
                .thenThrow(new RuntimeException("网络超时"));
        // 第二个订单查询成功
        Map<String, String> okResult = new HashMap<>();
        okResult.put("resultCode", "SUCCESS");
        when(allinpayIntlClient.queryOrder("ORD-OK-002")).thenReturn(okResult);

        int count = orderService.pollPayingOrders();

        // 只有第二个订单被处理
        assertEquals(1, count);
        assertEquals(OrderStatusEnum.PAID.getCode(), order2.getStatus());
        // 第一个订单状态不变
        assertEquals(OrderStatusEnum.PAYING.getCode(), order1.getStatus());
        verify(dispenseTicketMapper, times(1)).insert(any());
    }

    @Test
    public void testPollPayingOrders_multipleOrders_mixedResults() {
        OrderMain successOrder = buildPayingOrder("ORD-MIX-S");
        OrderMain failOrder = buildPayingOrder("ORD-MIX-F");
        when(orderMainMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(successOrder, failOrder));

        Map<String, String> successResult = new HashMap<>();
        successResult.put("resultCode", "SUCCESS");
        when(allinpayIntlClient.queryOrder("ORD-MIX-S")).thenReturn(successResult);

        Map<String, String> failResult = new HashMap<>();
        failResult.put("resultCode", "FAILED");
        when(allinpayIntlClient.queryOrder("ORD-MIX-F")).thenReturn(failResult);

        int count = orderService.pollPayingOrders();

        assertEquals(2, count);
        assertEquals(OrderStatusEnum.PAID.getCode(), successOrder.getStatus());
        assertEquals(OrderStatusEnum.PENDING.getCode(), failOrder.getStatus());
        verify(dispenseTicketMapper, times(1)).insert(any());
    }
}
