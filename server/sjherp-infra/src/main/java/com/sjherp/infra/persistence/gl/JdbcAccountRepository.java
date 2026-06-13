package com.sjherp.infra.persistence.gl;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.gl.Account;
import com.sjherp.domain.gl.AccountRepository;
import com.sjherp.domain.gl.AccountType;
import com.sjherp.domain.gl.BalanceDirection;

/**
 * 会计科目仓储的 MySQL 实现（M4-T01；代码风格照 {@code JdbcProductRepository}）。
 *
 * <p>单表档案（account），save 按编码 insert/update：新建插行并回填自增 id（{@link Account#assignId}）；
 * 已存在时按 id 更新启停/类别/层级与审计字段（创建审计字段 created_by/created_at 落库后不可变，不触碰）。
 * tenant_id v1.0 恒 0（ADR-002）；时间列 DATETIME(6) 按 UTC 读写。
 */
@Transactional
public class JdbcAccountRepository implements AccountRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, code, name, account_type, balance_dir, parent_code, level, is_leaf, enabled, "
                    + "is_preset, created_by, created_at, updated_by, updated_at FROM account ";

    private final JdbcTemplate jdbc;

    public JdbcAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Account account) {
        if (account.getId() == null) {
            insert(account);
        } else {
            update(account);
        }
    }

    private void insert(Account account) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO account (code, name, account_type, balance_dir, parent_code, level, "
                            + "is_leaf, enabled, is_preset, created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, account.getCode());
            ps.setString(2, account.getName());
            ps.setString(3, account.getType().name());
            ps.setString(4, account.getBalanceDir().name());
            if (account.getParentCode() == null) {
                ps.setNull(5, Types.VARCHAR);
            } else {
                ps.setString(5, account.getParentCode());
            }
            ps.setInt(6, account.getLevel());
            ps.setBoolean(7, account.isLeaf());
            ps.setBoolean(8, account.isEnabled());
            ps.setBoolean(9, account.isPreset());
            ps.setString(10, account.getCreatedBy());
            ps.setObject(11, toDb(account.getCreatedAt()));
            ps.setString(12, account.getUpdatedBy());
            ps.setObject(13, toDb(account.getUpdatedAt()));
            return ps;
        }, keyHolder);
        account.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得科目自增主键").longValue());
    }

    private void update(Account account) {
        // 创建审计字段（created_by/created_at）落库后不可变，更新不触碰；编码不可改（唯一键稳定口径）
        jdbc.update("UPDATE account SET name = ?, account_type = ?, balance_dir = ?, parent_code = ?, "
                        + "level = ?, is_leaf = ?, enabled = ?, is_preset = ?, updated_by = ?, "
                        + "updated_at = ? WHERE id = ?",
                account.getName(), account.getType().name(), account.getBalanceDir().name(),
                account.getParentCode(), account.getLevel(), account.isLeaf(), account.isEnabled(),
                account.isPreset(), account.getUpdatedBy(), toDb(account.getUpdatedAt()), account.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> findByCode(String code) {
        List<Account> rows = jdbc.query(SELECT_COLUMNS + "WHERE tenant_id = 0 AND code = ?",
                ROW_MAPPER, code);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Account> findAll() {
        return jdbc.query(SELECT_COLUMNS + "WHERE tenant_id = 0 ORDER BY code", ROW_MAPPER);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Account> findLeaf() {
        return jdbc.query(SELECT_COLUMNS + "WHERE tenant_id = 0 AND is_leaf = 1 ORDER BY code", ROW_MAPPER);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByCode(String code) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE tenant_id = 0 AND code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    private static final RowMapper<Account> ROW_MAPPER = (rs, rowNum) -> Account.restore(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            AccountType.valueOf(rs.getString("account_type")),
            BalanceDirection.valueOf(rs.getString("balance_dir")),
            rs.getString("parent_code"),
            rs.getInt("level"),
            rs.getBoolean("is_leaf"),
            rs.getBoolean("enabled"),
            rs.getBoolean("is_preset"),
            rs.getString("created_by"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("updated_by"),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
