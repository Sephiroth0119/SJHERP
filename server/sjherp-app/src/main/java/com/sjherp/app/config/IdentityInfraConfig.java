package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.domain.identity.PasswordHasher;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.identity.UserService;
import com.sjherp.infra.identity.BCryptPasswordHasher;
import com.sjherp.infra.persistence.identity.JdbcUserRepository;

/**
 * 用户/认证（identity，M2-T05）装配：仓储 MySQL 实现 + BCrypt 哈希 + 领域服务。
 *
 * <p>domain/infra 的类不加 Spring 注解（保持可独立测试），统一在此显式装配
 * （约定同 {@link CatalogInfraConfig}）。JWT 相关 bean 在
 * {@code com.sjherp.app.security.SecurityConfig}。
 */
@Configuration
public class IdentityInfraConfig {

    @Bean
    public UserRepository userRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcUserRepository(jdbcTemplate);
    }

    @Bean
    public PasswordHasher passwordHasher() {
        return new BCryptPasswordHasher();
    }

    /** 用户写操作唯一入口（创建/改密/重置/分配角色/启停 + 登录认证） */
    @Bean
    public UserService userService(UserRepository userRepository, PasswordHasher passwordHasher) {
        return new UserService(userRepository, passwordHasher);
    }
}
