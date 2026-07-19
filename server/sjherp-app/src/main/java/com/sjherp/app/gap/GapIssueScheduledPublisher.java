package com.sjherp.app.gap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import com.sjherp.domain.gap.GapIssueService;
import com.sjherp.domain.gap.GapIssueStatus;

@Component
@ConditionalOnExpression("'${sjherp.github.issue.auto-run:false}' == 'true' and '${sjherp.github.issue.enabled:false}' == 'true'")
public class GapIssueScheduledPublisher {
    private static final Logger log=LoggerFactory.getLogger(GapIssueScheduledPublisher.class);
    private final GapIssueService service;
    private final boolean manualApproval;
    private final int maxAttempts;
    public GapIssueScheduledPublisher(GapIssueService service,
            @Value("${sjherp.github.issue.manual-approval:true}") boolean manualApproval,
            @Value("${sjherp.github.issue.max-attempts:3}") int maxAttempts){
        this.service=service; this.manualApproval=manualApproval; this.maxAttempts=maxAttempts;
    }
    @Scheduled(fixedDelayString="${sjherp.github.issue.auto-run-delay-ms:60000}")
    public void publish(){
        int reclaimed=service.reclaimExpiredSending(java.time.Duration.ofMinutes(10),"system:gap-issue");
        if(reclaimed>0) log.warn("缺口 Issue 发送租约回收 {} 条",reclaimed);
        service.cluster("system:gap-issue");
        for(var c:service.dispatchable(maxAttempts, 200)) {
            if(c.attemptCount() >= maxAttempts) { log.warn("缺口 Issue 达到最大重试次数 candidateId={}",c.id()); continue; }
            if(manualApproval && c.status()==GapIssueStatus.PENDING) continue;
            if(c.status()!=GapIssueStatus.PENDING && c.status()!=GapIssueStatus.FAILED && c.status()!=GapIssueStatus.APPROVED) continue;
            try { if(c.status()==GapIssueStatus.PENDING) service.approve(c.id(),"system:gap-issue"); service.deliver(c.id(),"system:gap-issue"); log.info("缺口 Issue 自动投递 candidateId={}",c.id()); }
            catch(RuntimeException ex) { log.warn("缺口 Issue 自动投递失败 candidateId={} type={}",c.id(),ex.getClass().getSimpleName()); }
        }
    }
}
