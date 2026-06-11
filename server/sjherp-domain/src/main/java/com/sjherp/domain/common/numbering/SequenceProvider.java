package com.sjherp.domain.common.numbering;

/**
 * 序号供给接口（端口）。
 *
 * <p>领域层只声明"按作用域取下一个序号"的能力；生产实现由 infra 层
 * 提供（如数据库序号表 + 行锁，保证并发安全与持久化），领域内提供
 * {@link InMemorySequenceProvider} 供测试与开发使用。
 */
@FunctionalInterface
public interface SequenceProvider {

    /**
     * 取指定作用域的下一个序号。
     *
     * @param scopeKey 作用域键（如 PO-202606，见 {@link DocumentNumberRule#sequenceScopeKey}）
     * @return 下一个序号，从 1 开始，同一作用域内必须严格单调递增且并发安全
     */
    long next(String scopeKey);
}
