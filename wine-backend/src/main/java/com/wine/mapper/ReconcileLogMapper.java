package com.wine.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wine.domain.ReconcileLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ReconcileLogMapper extends BaseMapper<ReconcileLog> {

    /**
     * 批量插入对账日志（性能优化：百万级订单跑批避免逐行 INSERT）
     */
    @Insert("<script>" +
            "INSERT INTO reconcile_log (id, reconcile_date, order_no, order_status, ticket_status, pay_status, reconcile_result, anomaly_desc, handled) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.id}, #{item.reconcileDate}, #{item.orderNo}, #{item.orderStatus}, #{item.ticketStatus}, #{item.payStatus}, #{item.reconcileResult}, #{item.anomalyDesc}, #{item.handled})" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("list") List<ReconcileLog> list);
}
