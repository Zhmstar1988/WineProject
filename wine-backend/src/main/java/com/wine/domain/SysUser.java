package com.wine.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 用户表
 * 多租户：C端消费者、酒吧管理员/店员/财务、平台超管、酒商供应商
 * 合规：严禁存储 NRIC，仅记录 age_verified 标记
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    /** 手机号（新加坡） */
    private String phone;

    /** 三方登录唯一标识 (Apple/Google sub) */
    private String openid;

    /** 登录方式: 1-SMS 2-Apple 3-Google */
    private Integer loginType;

    /** 用户角色: 1-C端 2-酒吧管理员 3-店员 4-财务 5-平台超管 6-酒商 */
    private Integer role;

    /** 昵称 */
    private String nickname;

    /** 头像URL */
    private String avatar;

    /** 关联酒吧ID（酒吧角色） */
    private Long barId;

    /** 关联酒商ID（供应商角色） */
    private Long supplierId;

    /** 是否已完成法定年龄校验（>=18岁） */
    private Boolean ageVerified;

    /** 出生日期（仅后端计算年龄用，不存证件号） */
    private LocalDate birthDate;

    /** 账户状态: 1-正常 0-禁用 */
    private Integer status;
}
