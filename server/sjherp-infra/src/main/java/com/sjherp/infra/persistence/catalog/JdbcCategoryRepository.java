package com.sjherp.infra.persistence.catalog;

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

import com.sjherp.domain.catalog.Category;
import com.sjherp.domain.catalog.CategoryRepository;

/**
 * 商品类目仓储的 MySQL 实现（时间列 UTC 约定同 JdbcAgentSessionRepository）。
 */
@Transactional
public class JdbcCategoryRepository implements CategoryRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, name, parent_id, tree_level, created_by, created_at, updated_by, updated_at FROM category ";

    private static final RowMapper<Category> ROW_MAPPER = (rs, rowNum) -> Category.restore(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getObject("parent_id", Long.class),
            rs.getInt("tree_level"),
            rs.getString("created_by"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("updated_by"),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public JdbcCategoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Category category) {
        if (category.getId() == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO category (name, parent_id, tree_level, created_by, created_at, updated_by, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, category.getName());
                if (category.getParentId() == null) {
                    ps.setNull(2, Types.BIGINT);
                } else {
                    ps.setLong(2, category.getParentId());
                }
                ps.setInt(3, category.getLevel());
                ps.setString(4, category.getCreatedBy());
                ps.setObject(5, toDb(category.getCreatedAt()));
                ps.setString(6, category.getUpdatedBy());
                ps.setObject(7, toDb(category.getUpdatedAt()));
                return ps;
            }, keyHolder);
            category.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得自增主键").longValue());
        } else {
            // 父类目与层级创建时固化，更新只落名称与审计字段
            jdbc.update("UPDATE category SET name = ?, updated_by = ?, updated_at = ? WHERE id = ?",
                    category.getName(), category.getUpdatedBy(), toDb(category.getUpdatedAt()),
                    category.getId());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Category> findById(long id) {
        List<Category> rows = jdbc.query(SELECT_COLUMNS + "WHERE id = ?", ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Category> findByName(String name) {
        List<Category> rows = jdbc.query(SELECT_COLUMNS + "WHERE name = ?", ROW_MAPPER, name);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category> findAll() {
        // 层级 + id 排序，前端组树时父先于子
        return jdbc.query(SELECT_COLUMNS + "ORDER BY tree_level, id", ROW_MAPPER);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByParentId(long parentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM category WHERE parent_id = ?", Integer.class, parentId);
        return count != null && count > 0;
    }

    @Override
    public void deleteById(long id) {
        jdbc.update("DELETE FROM category WHERE id = ?", id);
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
