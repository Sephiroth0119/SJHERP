package com.sjherp.app.consistency;

import java.util.Objects;

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

    public ConsistencyReportService(ConsistencyCheckRunRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
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
}
