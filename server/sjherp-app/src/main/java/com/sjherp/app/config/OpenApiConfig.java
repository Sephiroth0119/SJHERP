package com.sjherp.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * knife4j / springdoc OpenAPI 全局配置（仅 dev/local profile 生效）。
 *
 * <ul>
 *   <li>API 标题：SJHERP API；版本与 CLAUDE.md 版本号同步维护；</li>
 *   <li>全局 JWT Bearer securityScheme：在 /doc.html 的 Authorize 按钮填入 token 后，
 *       所有接口请求头自动携带 Authorization: Bearer &lt;token&gt;；</li>
 *   <li>生产环境此 Bean 不装配，springdoc.api-docs.enabled=false 双重保险关闭文档。</li>
 * </ul>
 */
@Configuration
@Profile({"dev", "local"})
public class OpenApiConfig {

    /** JWT Bearer 安全方案名称，与全局 SecurityRequirement 引用名一致 */
    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI sjherpOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SJHERP API")
                        .description("SJHERP 下一代 Agent 原生 ERP — 后端接口文档（仅开发环境开放）")
                        .version("0.1.0-SNAPSHOT"))
                // 全局安全方案：所有接口默认需要 Bearer token
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("在 /api/auth/login 登录后将响应的 token 填入此处，" +
                                        "所有请求将自动携带 Authorization: Bearer <token>")));
    }
}
