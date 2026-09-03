package com.sjherp.app.consistency;

import com.sjherp.domain.consistency.ConsistencyCheckRun;

/** 一致性报告提交后的主动通道；通道故障不得改变已落库报告。 */
public interface ConsistencyProactiveChannel {

    void send(ConsistencyCheckRun run);
}
