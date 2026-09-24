package com.wine;

import com.wine.enums.DispenseTicketStatusEnum;
import com.wine.enums.OrderStatusEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 双单状态机测试
 * 验证交易主订单与硬件履约单的状态流转合法性
 */
public class StateMachineTest {

    @Test
    public void testOrderStatusEnum() {
        assertEquals(1, OrderStatusEnum.PENDING.getCode());
        assertEquals(2, OrderStatusEnum.PAYING.getCode());
        assertEquals(3, OrderStatusEnum.PAID.getCode());
        assertEquals(4, OrderStatusEnum.COMPLETED.getCode());
        assertEquals(5, OrderStatusEnum.REFUNDED.getCode());

        assertEquals(OrderStatusEnum.PAID, OrderStatusEnum.of(3));
        assertNull(OrderStatusEnum.of(99));
    }

    @Test
    public void testDispenseTicketStatusEnum() {
        assertEquals(1, DispenseTicketStatusEnum.READY.getCode());
        assertEquals(2, DispenseTicketStatusEnum.DISPENSING.getCode());
        assertEquals(3, DispenseTicketStatusEnum.SUCCESS.getCode());
        assertEquals(4, DispenseTicketStatusEnum.FAILED.getCode());
    }

    /**
     * 主订单正常流转：PENDING -> PAYING -> PAID -> COMPLETED
     */
    @Test
    public void testOrderNormalFlow() {
        int[] flow = {
                OrderStatusEnum.PENDING.getCode(),
                OrderStatusEnum.PAYING.getCode(),
                OrderStatusEnum.PAID.getCode(),
                OrderStatusEnum.COMPLETED.getCode()
        };
        assertEquals(1, flow[0]);
        assertEquals(2, flow[1]);
        assertEquals(3, flow[2]);
        assertEquals(4, flow[3]);
        // COMPLETED 是终态
        assertEquals("已完成", OrderStatusEnum.COMPLETED.getName());
    }

    /**
     * 主订单退款流转：PENDING -> PAYING -> PAID -> REFUNDED
     */
    @Test
    public void testOrderRefundFlow() {
        int[] flow = {
                OrderStatusEnum.PENDING.getCode(),
                OrderStatusEnum.PAYING.getCode(),
                OrderStatusEnum.PAID.getCode(),
                OrderStatusEnum.REFUNDED.getCode()
        };
        assertEquals(5, flow[3]);
        assertEquals("已退款", OrderStatusEnum.REFUNDED.getName());
    }

    /**
     * 履约单成功流转：READY -> DISPENSING -> SUCCESS
     */
    @Test
    public void testTicketSuccessFlow() {
        int[] flow = {
                DispenseTicketStatusEnum.READY.getCode(),
                DispenseTicketStatusEnum.DISPENSING.getCode(),
                DispenseTicketStatusEnum.SUCCESS.getCode()
        };
        assertEquals(1, flow[0]);
        assertEquals(2, flow[1]);
        assertEquals(3, flow[2]);
    }

    /**
     * 履约单失败流转：READY -> DISPENSING -> FAILED
     */
    @Test
    public void testTicketFailedFlow() {
        int[] flow = {
                DispenseTicketStatusEnum.READY.getCode(),
                DispenseTicketStatusEnum.DISPENSING.getCode(),
                DispenseTicketStatusEnum.FAILED.getCode()
        };
        assertEquals(4, flow[2]);
    }
}
