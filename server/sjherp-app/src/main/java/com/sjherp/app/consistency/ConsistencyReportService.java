package com.sjherp.app.consistency;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyCheckRunRepository;
import com.sjherp.domain.consistency.ConsistencyRunQuery;

/** 管理端一致性运行报告只读查询服务。 */
@Service
public class ConsistencyReportService {

    private static final long TENANT_ID = 0L;
    private final ConsistencyCheckRunRepository repository;
    private final Clock clock;

    public ConsistencyReportService(ConsistencyCheckRunRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ConsistencyReportService(ConsistencyCheckRunRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Transactional(readOnly = true)
    public PageResult<ConsistencyCheckRun> search(int page, int size) {
        return repository.search(TENANT_ID, new ConsistencyRunQuery(page, size));
    }

    @Transactional(readOnly = true)
    public ConsistencyCheckRun get(String runNo) {
        if (runNo == null || runNo.isBlank()) {
            throw new IllegalArgumentException("运行编号不能为空");
        }
        String normalized = runNo.strip();
        return repository.findByRunNo(TENANT_ID, normalized)
                .orElseThrow(() -> new ConsistencyReportNotFoundException(normalized));
    }

    /** 查询 UTC 自然日内最近一次报告；日期是查询口径，不改变报告本身的时间字段。 */
    @Transactional(readOnly = true)
    public Optional<ConsistencyCheckRun> latestOn(LocalDate date) {
        Objects.requireNonNull(date, "日期不能为空");
        Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        return repository.findLatestByCompletedAtBetween(TENANT_ID, start, start.plusSeconds(86_400));
    }

    /** 查询最近一次显式运行，供用户未指定日期时回看最新报告。 */
    @Transactional(readOnly = true)
    public Optional<ConsistencyCheckRun> latest() {
        return repository.search(TENANT_ID, new ConsistencyRunQuery(1, 1)).items().stream()
                .findFirst()
                .flatMap(summary -> repository.findByRunNo(TENANT_ID, summary.runNo()));
    }

    /** 当前查询时刻使用 UTC，保证与报告 DATETIME(6) 存储口径一致。 */
    public LocalDate todayUtc() {
        return LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }
}
