package com.sjherp.app.security;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.identity.UserService;

/**
 * 初始管理员账户初始化器（首次启动时自动创建 admin 账户）。
 *
 * <p>安全要求（公开仓库）：
 * <ul>
 *   <li>admin 密码不再硬编码在迁移脚本中，必须通过环境变量 SJHERP_ADMIN_PASSWORD 配置；</li>
 *   <li>首次启动（数据库中无用户）时，使用环境变量中的密码创建 admin 账户；</li>
 *   <li>如果数据库中无用户且环境变量未配置/不符合密码强度，启动失败（fail-fast）；</li>
 *   <li>如果数据库中已有用户，跳过初始化（支持已有部署升级）。</li>
 * </ul>
 */
@Component
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_DISPLAY_NAME = "系统管理员";
    private static final String OPERATOR = "system";

    private final UserRepository userRepository;
    private final UserService userService;
    private final AdminProperties adminProperties;

    public AdminInitializer(UserRepository userRepository, UserService userService, AdminProperties adminProperties) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.adminProperties = adminProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.findByUsername(ADMIN_USERNAME).isPresent()) {
            log.info("admin 账户已存在，跳过初始化");
            return;
        }

        if (!adminProperties.hasPassword()) {
            throw new IllegalStateException(
                    "首次启动需要配置初始管理员密码：设置环境变量 SJHERP_ADMIN_PASSWORD（≥ 8 位、字母+数字）");
        }

        try {
            userService.create(ADMIN_USERNAME, ADMIN_DISPLAY_NAME, adminProperties.password(),
                    Set.of(Role.ADMIN), OPERATOR);
            log.info("初始管理员账户 admin 创建成功（密码来自环境变量 SJHERP_ADMIN_PASSWORD）");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "初始管理员密码不符合强度要求（≥ 8 位、字母+数字）：" + e.getMessage(), e);
        }
    }
}
