package com.wine.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wine.domain.BarInventory;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface BarInventoryMapper extends BaseMapper<BarInventory> {

    /**
     * 原子增加库存（验收入库时调用）
     * 若记录不存在需先 insert，此方法仅做增量更新
     */
    @Update("UPDATE bar_inventory SET quantity = quantity + #{qty} " +
            "WHERE bar_id = #{barId} AND wine_sku_id = #{wineSkuId} AND deleted = 0")
    int addQuantity(@Param("barId") Long barId,
                    @Param("wineSkuId") Long wineSkuId,
                    @Param("qty") Integer qty);

    /**
     * 原子扣减库存（换瓶出库时调用），仅当 quantity >= qty 时成功
     */
    @Update("UPDATE bar_inventory SET quantity = quantity - #{qty} " +
            "WHERE bar_id = #{barId} AND wine_sku_id = #{wineSkuId} " +
            "AND quantity >= #{qty} AND deleted = 0")
    int deductQuantity(@Param("barId") Long barId,
                       @Param("wineSkuId") Long wineSkuId,
                       @Param("qty") Integer qty);
}
