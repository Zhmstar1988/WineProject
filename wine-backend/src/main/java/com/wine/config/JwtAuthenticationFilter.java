package com.wine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wine.common.JwtUtil;
import com.wine.common.LoginUser;
import com.wine.common.Result;
import com.wine.common.UserContextHolder;
import com.wine.domain.SysUser;
import com.wine.mapper.SysUserMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT 认证过滤器
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private SysUserMapper sysUserMapper;

    @Value("${jwt.header}")
    private String header;

    @Value("${jwt.prefix}")
    private String prefix;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = null;
        String authHeader = request.getHeader(header);
        if (authHeader != null && authHeader.startsWith(prefix)) {
            token = authHeader.substring(prefix.length());
        }
        // 兼容管理后台页面（浏览器导航不携带 Authorization 头，从 Cookie 读取）
        if (token == null && request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie c : request.getCookies()) {
                if ("token".equals(c.getName())) {
                    token = c.getValue();
                    break;
                }
            }
        }
        if (token != null) {
            try {
                if (jwtUtil.validateToken(token)) {
                    Long userId = jwtUtil.getUserId(token);
                    SysUser user = sysUserMapper.selectById(userId);
                    if (user != null && user.getStatus() == 1) {
                        LoginUser loginUser = LoginUser.from(user);
                        UserContextHolder.set(loginUser);
                        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                                loginUser, null,
                                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
            } catch (Exception e) {
                // token 无效，继续走匿名
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
