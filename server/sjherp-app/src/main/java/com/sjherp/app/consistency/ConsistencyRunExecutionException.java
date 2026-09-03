package com.sjherp.app.consistency;

final class ConsistencyRunExecutionException extends RuntimeException {

    static final String SAFE_MESSAGE = "一致性校验执行失败，请稍后重试";

    ConsistencyRunExecutionException() {
        super(SAFE_MESSAGE, null, false, false);
    }
}
