package com.wine.common;

/**
 * 当前用户上下文持有器（ThreadLocal）
 */
public class UserContextHolder {

    private static final ThreadLocal<LoginUser> CONTEXT = new ThreadLocal<>();

    public static void set(LoginUser user) {
        CONTEXT.set(user);
    }

    public static LoginUser get() {
        return CONTEXT.get();
    }

    public static Long getUserId() {
        LoginUser u = CONTEXT.get();
        return u != null ? u.getUserId() : null;
    }

    public static Integer getRole() {
        LoginUser u = CONTEXT.get();
        return u != null ? u.getRole() : null;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
