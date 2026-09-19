package cn.teamone.app.config;

import cn.teamone.app.auth.JwtAuthFilter;
import cn.teamone.shared.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter, ObjectMapper om,
            @org.springframework.beans.factory.annotation.Value(
                    "${teamone.security.openapi-public:true}") boolean openapiPublic) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> {
                var rules = a.requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh",
                                              "/actuator/health", "/actuator/info", "/ws").permitAll();
                // W4: git post-receive hook 回调——permitAll 仅此一路，鉴权走 X-TeamOne-Hook-Token
                // 共享口令（GitHookController 常量时间比较；07 §2.2），其余 /api/** 仍强制 JWT
                rules = rules.requestMatchers("/api/v1/git/hooks/post-receive").permitAll();
                // OpenAPI 契约端点：dev 阶段公开（默认 true），M3 私有化交付收紧为 false
                if (openapiPublic) {
                    rules = rules.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                            .permitAll();
                }
                rules.requestMatchers("/api/**").authenticated().anyRequest().denyAll();
            })
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                res.setCharacterEncoding(StandardCharsets.UTF_8.name());
                res.getWriter().write(om.writeValueAsString(
                        ApiError.of(cn.teamone.shared.api.ErrorCode.PLT_4010)));
            }))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
