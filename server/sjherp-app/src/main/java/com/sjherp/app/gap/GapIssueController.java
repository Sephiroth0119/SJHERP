package com.sjherp.app.gap;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.gap.*;

@RestController
@RequestMapping("/api/gap-issues")
@PreAuthorize("@perm.has('gap:issue')")
public class GapIssueController {
    private final GapIssueService service;
    public GapIssueController(GapIssueService service){this.service=service;}
    @PostMapping("/candidates") public List<GapIssueCandidate> cluster(){return service.cluster(CurrentUser.operator());}
    @GetMapping("/candidates") public List<GapIssueCandidate> list(){return service.list();}
    @PostMapping("/candidates/{id}/approve") public GapIssueCandidate approve(@PathVariable long id){return service.approve(id,CurrentUser.operator());}
    @PostMapping("/candidates/{id}/deliver") public GapIssueCandidate deliver(@PathVariable long id){return service.deliver(id,CurrentUser.operator());}
    @PostMapping("/reclaim-expired") public int reclaimExpired(){return service.reclaimExpiredSending(java.time.Duration.ofMinutes(10),CurrentUser.operator());}
}
