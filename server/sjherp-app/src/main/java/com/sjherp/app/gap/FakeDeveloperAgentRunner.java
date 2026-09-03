package com.sjherp.app.gap;
import com.sjherp.domain.gap.*;
/** 默认安全 runner：只生成可审计的演示结果，不执行 shell、Git 或外部网络。 */
public final class FakeDeveloperAgentRunner implements DeveloperAgentRunner {
    @Override public String kind(){return "FAKE_DEMO";}
    @Override public Result run(RunRequest request) { return new Result(java.util.List.of("fake://generated-code", "fake://generated-tests"), true, true, false, null, "demo evidence only; CI not executed"); }
}
