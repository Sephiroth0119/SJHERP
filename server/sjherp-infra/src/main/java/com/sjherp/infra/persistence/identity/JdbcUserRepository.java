package com.sjherp.infra.persistence.identity;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.User;
import com.sjherp.domain.identity.UserRepository;

/**
 * 用户仓储的 MySQL 实现（表 sys_user，V6 迁移；模式样板：{@code JdbcWarehouseRepository}）。
 *
 * <p>角色集合存 JSON 数组列（roles JSON，如 ["ADMIN","SALES"]）——小企业角色为
 * 固定枚举且数量极少，无需 user_role 关联表（M2-T05 二选一决策）。
 * 时间列 DATETIME(6) 一律按 UTC 读写（约定同 JdbcAgentSessionRepository）。
 */
@Transactional
public class JdbcUserRepository implements UserRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, username, display_name, password_hash, roles, status, "
                    + "created_by, created_at, updated_by, updated_at FROM sys_user ";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RowMapper<User> ROW_MAPPER = (rs, rowNum) -> User.restore(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("password_hash"),
            rolesFromJson(rs.getString("roles")),
            ArchiveStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("updated_by"),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public JdbcUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(User user) {
        if (user.getId() == null) {
            insert(user);
        } else {
            update(user);
        }
    }

    private void insert(User user) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sys_user (username, display_name, password_hash, roles, status, "
                            + "created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getDisplayName());
            ps.setString(3, user.getPasswordHash());
            ps.setString(4, rolesToJson(user.getRoles()));
            ps.setString(5, user.getStatus().name());
            ps.setString(6, user.getCreatedBy());
            ps.setObject(7, toDb(user.getCreatedAt()));
            ps.setString(8, user.getUpdatedBy());
            ps.setObject(9, toDb(user.getUpdatedAt()));
            return ps;
        }, keyHolder);
        user.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得自增主键").longValue());
    }

    private void update(User user) {
        // username 与创建审计字段（created_by/created_at）落库后不可变，更新不触碰
        jdbc.update("UPDATE sys_user SET display_name = ?, password_hash = ?, roles = ?, status = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                user.getDisplayName(), user.getPasswordHash(), rolesToJson(user.getRoles()),
                user.getStatus().name(), user.getUpdatedBy(), toDb(user.getUpdatedAt()), user.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findById(long id) {
        List<User> rows = jdbc.query(SELECT_COLUMNS + "WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByUsername(String username) {
        List<User> rows = jdbc.query(SELECT_COLUMNS + "WHERE username = ?", ROW_MAPPER, username);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByUsername(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> findAll() {
        return jdbc.query(SELECT_COLUMNS + "ORDER BY id", ROW_MAPPER);
    }

    // ---------------------------------------------------------------- 角色 JSON 编解码

    private static String rolesToJson(Set<Role> roles) {
        List<String> names = new ArrayList<>(roles.size());
        for (Role role : roles) {
            names.add(role.name());
        }
        try {
            return MAPPER.writeValueAsString(names);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("角色集合序列化失败", e);
        }
    }

    private static Set<Role> rolesFromJson(String json) {
        try {
            String[] names = MAPPER.readValue(json, String[].class);
            Set<Role> roles = EnumSet.noneOf(Role.class);
            for (String name : names) {
                roles.add(Role.valueOf(name));
            }
            return roles;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("角色列 JSON 解析失败: " + json, e);
        }
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
