package com.wine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wine.common.Result;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 配置
 * 无状态 JWT 鉴权
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Resource
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Resource
    private ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 公开接口
                .requestMatchers(
                    "/auth/**",
                    "/health",
                    "/actuator/**",
                    "/menu/**",
                    "/bar/by-code/**",
                    "/payment/notify",
                    "/dispense/callback",
                    "/dispenser/callback",
                    "/api/dispenser/*/heartbeat",
                    "/admin/page/login",
                    "/admin/static/**",
                    "/h2-console/**",
                    "/doc.html", "/webjars/**", "/v3/api-docs/**", "/swagger-ui/**"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, resp, e) -> {
                    String uri = req.getServletPath();
                    if (uri.startsWith("/admin/page") && !uri.endsWith("/login")) {
                        resp.sendRedirect(req.getContextPath() + "/admin/page/login");
                    } else {
                        resp.setContentType("application/json;charset=UTF-8");
                        resp.getWriter().write(objectMapper.writeValueAsString(Result.error(401, "未登录或token已过期")));
                    }
                })
                .accessDeniedHandler((req, resp, e) -> {
                    resp.setContentType("application/json;charset=UTF-8");
                    resp.getWriter().write(objectMapper.writeValueAsString(Result.error(403, "无权限访问")));
                })
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        // 允许 H2 console frame
        http.headers(h -> h.frameOptions(f -> f.disable()));

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
