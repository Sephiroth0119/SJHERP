package com.sjherp.app.consistency;

import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public final class CoreSqlAssertionRule implements ConsistencyRule {

    private final ConsistencyCheckService service;

    public CoreSqlAssertionRule(ConsistencyCheckService service) {
        this.service = Objects.requireNonNull(service, "service 不能为空");
    }

    @Override
    public String code() {
        return "CORE_SQL_ASSERTIONS";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public Kind kind() {
        return Kind.SQL_ASSERTION;
    }

    @Override
    public Result evaluate(Context context) {
        return Result.deterministic(service.check().breaks());
    }
}
