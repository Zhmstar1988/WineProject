package com.wine.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.common.Result;
import com.wine.common.UserContextHolder;
import com.wine.domain.LossAuditLog;
import com.wine.domain.OrderMain;
import com.wine.domain.SysUser;
import com.wine.domain.WineSku;
import com.wine.enums.UserRoleEnum;
import com.wine.mapper.LossAuditLogMapper;
import com.wine.mapper.OrderMainMapper;
import com.wine.mapper.SysUserMapper;
import com.wine.mapper.WineSkuMapper;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 供酒商管理接口（平台运营端）
 * 以及损耗审计查询、用户管理
 */
@RestController
@RequestMapping("/admin")
public class AdminExtController {

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private LossAuditLogMapper lossAuditLogMapper;

    @Resource
    private OrderMainMapper orderMainMapper;

    @Resource
    private WineSkuMapper wineSkuMapper;

    // ==================== 用户管理（平台运营端） ====================

    /** 用户列表（平台端）：支持按角色、状态筛选，按手机号/昵称搜索 */
    @GetMapping("/users")
    public Result<List<SysUser>> listUsers(
            @RequestParam(name = "role", required = false) Integer role,
            @RequestParam(name = "status", required = false) Integer status,
            @RequestParam(name = "keyword", required = false) String keyword) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<>();
        if (role != null) qw.eq(SysUser::getRole, role);
        if (status != null) qw.eq(SysUser::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(SysUser::getPhone, keyword)
                    .or().like(SysUser::getNickname, keyword));
        }
        qw.orderByDesc(SysUser::getCreateTime);
        return Result.success(sysUserMapper.selectList(qw));
    }

    /**
     * 新增用户（平台端）
     * <p>
     * 权限约束：
     * 1. 禁止新建平台管理员（role=5），平台管理员仅通过数据库预设账号初始化；
     * 2. 禁止新建C端消费者（role=1），C端用户仅能通过APP侧手机号/三方登录注册；
     * 3. 仅允许创建酒吧管理员（role=2）和供酒商（role=6）。
     */
    @PostMapping("/users")
    public Result<SysUser> createUser(@RequestBody SysUser user) {
        // 校验手机号唯一
        Long exist = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, user.getPhone()));
        if (exist != null && exist > 0) {
            return Result.error("手机号已存在");
        }
        // 禁止新建平台管理员（仅预设账号）
        if (user.getRole() != null && user.getRole() == UserRoleEnum.PLATFORM_ADMIN.getCode()) {
            return Result.error(403, "禁止新建平台管理员，平台管理员为预设账号");
        }
        // 禁止新建C端消费者（仅APP侧注册）
        if (user.getRole() != null && user.getRole() == UserRoleEnum.CUSTOMER.getCode()) {
            return Result.error(403, "禁止后台新建C端用户，C端用户仅能通过APP注册");
        }
        // 仅允许酒吧管理员和供酒商
        if (user.getRole() == null) {
            user.setRole(UserRoleEnum.BAR_ADMIN.getCode());
        } else if (user.getRole() != UserRoleEnum.BAR_ADMIN.getCode()
                && user.getRole() != UserRoleEnum.SUPPLIER.getCode()) {
            return Result.error(403, "仅允许创建酒吧管理员或供酒商账号");
        }
        if (user.getStatus() == null) user.setStatus(1);
        if (user.getLoginType() == null) user.setLoginType(1);
        if (user.getAgeVerified() == null) user.setAgeVerified(true);
        sysUserMapper.insert(user);
        return Result.success(user);
    }

    /**
     * 编辑用户（平台端）：手机号不可修改，角色不可变更为平台管理员或C端用户
     */
    @PutMapping("/users/{id}")
    public Result<Void> updateUser(@PathVariable Long id, @RequestBody SysUser user) {
        SysUser exist = sysUserMapper.selectById(id);
        if (exist == null) return Result.error("用户不存在");
        // 禁止变更为平台管理员（预设账号）或C端用户（仅APP注册）
        if (user.getRole() != null
                && (user.getRole() == UserRoleEnum.PLATFORM_ADMIN.getCode()
                    || user.getRole() == UserRoleEnum.CUSTOMER.getCode())) {
            return Result.error(403, "禁止将用户角色变更为平台管理员或C端用户");
        }
        user.setId(id);
        user.setPhone(exist.getPhone()); // 手机号不可修改
        sysUserMapper.updateById(user);
        return Result.success();
    }

    /** 删除用户（平台端） */
    @DeleteMapping("/users/{id}")
    public Result<Void> deleteUser(@PathVariable Long id) {
        sysUserMapper.deleteById(id);
        return Result.success();
    }

    /** 切换用户启用/禁用状态 */
    @PutMapping("/users/{id}/status")
    public Result<Void> toggleUserStatus(@PathVariable Long id, @RequestParam(name = "status") Integer status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setStatus(status);
        sysUserMapper.updateById(user);
        return Result.success();
    }

    /** 供酒商列表（平台端） */
    @GetMapping("/suppliers")
    public Result<List<SysUser>> listSuppliers() {
        return Result.success(sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getRole, UserRoleEnum.SUPPLIER.getCode())
                        .orderByDesc(SysUser::getCreateTime)));
    }

    /** 损耗审计列表 */
    @GetMapping("/loss-audit")
    public Result<List<LossAuditLog>> listLossAudit() {
        return Result.success(lossAuditLogMapper.selectList(
                new LambdaQueryWrapper<LossAuditLog>().orderByDesc(LossAuditLog::getCreateTime)));
    }

    /** 供酒商供酒记录（供酒商端）：查询自己供应酒款的订单 */
    @GetMapping("/supplier-orders")
    public Result<List<OrderMain>> listSupplierOrders() {
        Long supplierId = UserContextHolder.get().getSupplierId();
        // 查询该供应商的所有酒款SKU
        List<WineSku> wines = wineSkuMapper.selectList(
                new LambdaQueryWrapper<WineSku>().eq(WineSku::getSupplierId, supplierId));
        List<Long> skuIds = wines.stream().map(WineSku::getId).collect(Collectors.toList());
        if (skuIds.isEmpty()) {
            return Result.success(List.of());
        }
        return Result.success(orderMainMapper.selectList(
                new LambdaQueryWrapper<OrderMain>()
                        .in(OrderMain::getWineSkuId, skuIds)
                        .orderByDesc(OrderMain::getCreateTime)));
    }
}
