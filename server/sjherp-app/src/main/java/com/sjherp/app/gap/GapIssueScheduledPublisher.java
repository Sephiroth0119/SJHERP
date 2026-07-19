package com.sjherp.app.gap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sjherp.domain.gap.GapIssueService;
import com.sjherp.domain.gap.GapIssueStatus;

@Component
@ConditionalOnProperty(name="sjherp.github.issue.auto-run", havingValue="true")
public class GapIssueScheduledPublisher {
    private static final Logger log=LoggerFactory.getLogger(GapIssueScheduledPublisher.class);
    private final GapIssueService service;
    private final boolean manualApproval;
    public GapIssueScheduledPublisher(GapIssueService service,@Value("${sjherp.github.issue.manual-approval:true}") boolean manualApproval){this.service=service;this.manualApproval=manualApproval;}
    @Scheduled(fixedDelayString="${sjherp.github.issue.auto-run-delay-ms:60000}")
    public void publish(){
        int reclaimed=service.reclaimExpiredSending(java.time.Duration.ofMinutes(10));
        if(reclaimed>0) log.warn("缺口 Issue 发送租约回收 {} 条",reclaimed);
        for(var c:service.cluster()) {
            if(manualApproval || (c.status()!=GapIssueStatus.PENDING && c.status()!=GapIssueStatus.FAILED && c.status()!=GapIssueStatus.APPROVED)) continue;
            try { if(c.status()==GapIssueStatus.PENDING) service.approve(c.id(),"system:gap-issue"); service.deliver(c.id()); log.info("缺口 Issue 自动投递 candidateId={}",c.id()); }
            catch(RuntimeException ex) { log.warn("缺口 Issue 自动投递失败 candidateId={} type={}",c.id(),ex.getClass().getSimpleName()); }
        }
    }
}
