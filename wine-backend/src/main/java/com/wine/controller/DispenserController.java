package com.wine.controller;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wine.common.Result;
import com.wine.domain.Dispenser;
import com.wine.mapper.DispenserMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 分酒机设备接口
 * IoT 网关上报心跳、状态等
 */
@Slf4j
@RestController
@RequestMapping("/api/dispenser")
public class DispenserController {

    @Resource
    private DispenserMapper dispenserMapper;

    /**
     * 分酒机心跳上报
     * 设备定期（如每30秒）调用，更新 last_heartbeat 和在线状态
     *
     * @param id   分酒机ID
     * @param body 可选：firmwareVersion / status(0离线 1在线 2故障)
     */
    @PostMapping("/{id}/heartbeat")
    public Result<Map<String, Object>> heartbeat(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        Dispenser d = dispenserMapper.selectById(id);
        if (d == null) {
            return Result.error(404, "分酒机不存在");
        }
        LambdaUpdateWrapper<Dispenser> uw = new LambdaUpdateWrapper<>();
        uw.eq(Dispenser::getId, id)
                .set(Dispenser::getLastHeartbeat, LocalDateTime.now())
                .set(Dispenser::getStatus, 1); // 心跳即在线
        if (body != null) {
            if (body.containsKey("firmwareVersion")) {
                uw.set(Dispenser::getFirmwareVersion, String.valueOf(body.get("firmwareVersion")));
            }
            if (body.containsKey("status")) {
                try {
                    int status = Integer.parseInt(String.valueOf(body.get("status")));
                    if (status >= 0 && status <= 2) {
                        uw.set(Dispenser::getStatus, status);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        dispenserMapper.update(null, uw);
        log.debug("分酒机心跳: id={}, firmware={}", id, body != null ? body.get("firmwareVersion") : null);
        return Result.success(Map.of("lastHeartbeat", LocalDateTime.now().toString()));
    }
}
