package com.sjherp.infra.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.numbering.SequenceProvider;

/**
 * 序号供给的数据库实现（M2-T01 待办落地，编号生成自此可用于生产）。
 *
 * <p>doc_sequence 表 + SELECT ... FOR UPDATE 行锁递增：
 * <ul>
 *   <li>并发安全：同一作用域的并发取号被行锁串行化，绝不重号；</li>
 *   <li>重启不重号：当前值持久化在表中，进程重启/热部署后序号延续；</li>
 *   <li>事务边界：REQUIRES_NEW 独立提交——取号即占用，外层业务回滚时
 *       序号留空洞而不回收（编号永不重复优先于编号连续，且避免行锁
 *       被长业务事务持有导致取号串行化扩大）。</li>
 * </ul>
 *
 * <p>首次取号竞态：两个线程同时为新作用域 INSERT 时，唯一键约束保证
 * 只有一个成功，失败方捕获 {@link DuplicateKeyException} 后退回锁行递增路径。
 */
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class JdbcSequenceProvider implements SequenceProvider {

    private final JdbcTemplate jdbc;

    public JdbcSequenceProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long next(String scopeKey) {
        Objects.requireNonNull(scopeKey, "scopeKey 不能为空");

        Long current = lockCurrentValue(scopeKey);
        if (current == null) {
            // 作用域首号：尝试插入 1；并发撞唯一键则退回锁行递增
            try {
                jdbc.update("INSERT INTO doc_sequence (scope_key, current_value, updated_at) VALUES (?, 1, ?)",
                        scopeKey, utcNow());
                return 1L;
            } catch (DuplicateKeyException e) {
                current = lockCurrentValue(scopeKey);
                if (current == null) {
                    // 理论不可达：插入撞唯一键说明行已存在
                    throw new IllegalStateException("doc_sequence 行插入冲突后仍不可见: " + scopeKey);
                }
            }
        }

        long nextValue = current + 1;
        jdbc.update("UPDATE doc_sequence SET current_value = ?, updated_at = ? WHERE scope_key = ?",
                nextValue, utcNow(), scopeKey);
        return nextValue;
    }

    /** 行锁读取当前值（行不存在返回 null）；锁随本事务提交释放 */
    private Long lockCurrentValue(String scopeKey) {
        List<Long> rows = jdbc.query(
                "SELECT current_value FROM doc_sequence WHERE scope_key = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong(1), scopeKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** DATETIME(6) 列无时区，统一按 UTC 存（与 JdbcAgentSessionRepository 约定一致） */
    private static LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }
}
