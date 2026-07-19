package com.sjherp.app.gap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.sjherp.domain.gap.GapIssueService;
import com.sjherp.domain.gap.GapIssueStatus;

@Component
@ConditionalOnProperty(name="sjherp.github.issue.auto-run", havingValue="true")
public class GapIssueScheduledPublisher {
    private final GapIssueService service;
    private final boolean manualApproval;
    public GapIssueScheduledPublisher(GapIssueService service,@Value("${sjherp.github.issue.manual-approval:true}") boolean manualApproval){this.service=service;this.manualApproval=manualApproval;}
    @Scheduled(fixedDelayString="${sjherp.github.issue.auto-run-delay-ms:60000}")
    public void publish(){
        for(var c:service.cluster()) {
            if(manualApproval || c.status()!=GapIssueStatus.PENDING) continue;
            try { service.approve(c.id(),"system:gap-issue"); service.deliver(c.id()); } catch(RuntimeException ignored) { /* 状态与失败已由领域层保留 */ }
        }
    }
}
