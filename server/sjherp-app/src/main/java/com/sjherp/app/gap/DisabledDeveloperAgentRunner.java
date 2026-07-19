package com.sjherp.app.gap;
import com.sjherp.domain.gap.*;
public final class DisabledDeveloperAgentRunner implements DeveloperAgentRunner {
    @Override public boolean available(){return false;}
    @Override public String kind(){return "DISABLED";}
    @Override public Result run(RunRequest request) { throw new GapIssueDisabledException("developer agent execution is disabled"); }
}
