package com.wine.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wine.domain.DispenserSlot;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface DispenserSlotMapper extends BaseMapper<DispenserSlot> {

    /**
     * 原子扣减瓶位在机容量（防超卖）
     * 仅当 current_capacity >= volumeMl 时扣减成功，返回 1；否则返回 0
     */
    @Update("UPDATE dispenser_slot SET current_capacity = current_capacity - #{volumeMl} " +
            "WHERE dispenser_id = #{dispenserId} AND slot_no = #{slotNo} " +
            "AND current_capacity >= #{volumeMl} AND deleted = 0")
    int deductCapacity(@Param("dispenserId") Long dispenserId,
                       @Param("slotNo") Integer slotNo,
                       @Param("volumeMl") Integer volumeMl);

    /**
     * 返还瓶位容量（退款/取消/超时关闭时调用）
     * 不超过初始容量
     */
    @Update("UPDATE dispenser_slot SET current_capacity = LEAST(initial_capacity, current_capacity + #{volumeMl}) " +
            "WHERE dispenser_id = #{dispenserId} AND slot_no = #{slotNo} AND deleted = 0")
    int restoreCapacity(@Param("dispenserId") Long dispenserId,
                        @Param("slotNo") Integer slotNo,
                        @Param("volumeMl") Integer volumeMl);
}
