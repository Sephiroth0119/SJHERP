package com.sjherp.infra.persistence;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * DB 集成测试基类（X-2）：Testcontainers MySQL 8.4 + 全量 Flyway 迁移。
 *
 * <p>容器与迁移在静态初始化块中只执行一次（同一 JVM 内所有子类共享，
 * surefire 默认单 fork 复用），JVM 退出时由 Testcontainers Ryuk 自动回收容器。
 *
 * <p>迁移跑 classpath:db/migration 下的<b>全部</b>版本（不硬编码版本号，
 * 新增迁移自动纳入）——验证迁移脚本在真实 MySQL 上可执行本身就是测试价值。
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * 手动/CI 运行：<pre>mvn test -pl sjherp-infra -Dgroups=integration-db -DexcludedGroups=none</pre>
 */
@Tag("integration-db")
public abstract class MySqlContainerTestBase {

    /** MySQL 8.4（CLAUDE.md 技术栈 MySQL 8.x 的当前 LTS 版本） */
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    /** 已完成全部迁移的数据源上的 JdbcTemplate（各仓储实现直接复用） */
    protected static final JdbcTemplate jdbc;

    static {
        MYSQL.start();
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        // 跑 classpath 全部迁移（V1..VN）；任何脚本失败即测试失败
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    /** 测试数据唯一后缀（同一容器内多个测试类共享库，编码类字段须避免唯一键冲突） */
    protected static String uniqueSuffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
