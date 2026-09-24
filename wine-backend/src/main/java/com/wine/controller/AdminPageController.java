package com.wine.controller;

import com.wine.common.LoginUser;
import com.wine.common.UserContextHolder;
import com.wine.enums.UserRoleEnum;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 管理后台页面路由（Thymeleaf 模板渲染）
 * 页面通过 AJAX 调用 /api/admin/** 接口获取数据
 * 角色：平台运营超管(5) / 酒吧管理员(2) / 酒商供应商(6)
 */
@Controller
@RequestMapping("/admin/page")
public class AdminPageController {

    private void setRole(Model model) {
        LoginUser u = UserContextHolder.get();
        if (u != null) {
            model.addAttribute("role", u.getRole());
            UserRoleEnum re = UserRoleEnum.of(u.getRole());
            model.addAttribute("roleName", re != null ? re.getName() : "未知角色");
        }
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        setRole(model);
        model.addAttribute("pageTitle", "运营概览");
        return "dashboard";
    }

    @GetMapping("/bars")
    public String bars(Model model) {
        setRole(model);
        model.addAttribute("pageTitle", "酒吧管理");
        return "bars";
    }

    @GetMapping("/dispensers")
    public String dispensers(Model model) {
        setRole(model);
        model.addAttribute("pageTitle", "分酒机管理");
        return "dispensers";
    }

    @GetMapping("/slots")
    public String slots(Model model) {
        setRole(model);
        model.addAttribute("pageTitle", "瓶位余量监控");
        return "slots";
    }

    @GetMapping("/slots/{dispenserId}")
    public String slots(@PathVariable Long dispenserId, Model model) {
        setRole(model);
        model.addAttribute("pageTitle", "瓶位余量监控");
        return "slots";
    }

    @GetMapping("/orders")
    public String orders(Model model) {
        setRole(model);
        model.addAttribute("pageTitle", "订单流水");
        return "orders";
    }

    @GetMapping("/change-bottle")
    public String changeBottle(Model model) {
        setRole(model);
        model.addAttribute("pageTitle", "换瓶SOP");
        return "change-bottle";
    }

    @GetMapping("/reconcile")
    public String reconcile(Model model) {
        setRole(model);
        model.addAttribute("pageTitle", "履约核对");
        return "reconcile";
    }

    @GetMapping("/loss-audit")
    public String lossAudit(Model model) {
        setRole(model);
        model.addAttribute("pageTitle", "损耗审计");
        return "loss-audit";
    }

    @GetMapping("/wines")
    public String wines(Model model) {
        setRole(model);
        model.addAttribute("pageTitle", "酒款管理");
        return "wines";
    }

    @GetMapping("/suppliers")
    public String suppliers(Model model) {
        setRole(model);
        model.addAttribute("pageTitle", "供酒商管理");
        return "suppliers";
    }

    @GetMapping("/supplier-orders")
    public String supplierOrders(Model model) {
        setRole(model);
        model.addAttribute("pageTitle", "供酒记录");
        return "supplier-orders";
    }

    @GetMapping("/users")
    public String users(Model model) {
        setRole(model);
        model.addAttribute("pageTitle", "用户管理");
        return "users";
    }
}
