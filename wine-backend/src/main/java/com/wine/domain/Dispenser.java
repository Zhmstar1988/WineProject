package com.wine.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 分酒机设备表
 * 智能恒温分酒机 IoT 网关
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dispenser")
public class Dispenser extends BaseEntity {

    /** 所属酒吧ID */
    private Long barId;

    /** 设备编号 UUID */
    private String deviceNo;

    /** MAC地址 */
    private String mac;

    /** 设备名称 */
    private String deviceName;

    /** 瓶位数量 */
    private Integer slotCount;

    /** 设备状态: 0-离线 1-在线 2-故障 */
    private Integer status;

    /** 最后心跳时间 */
    private LocalDateTime lastHeartbeat;

    /** 固件版本 */
    private String firmwareVersion;
}
