package com.sjherp.app.gap;

import com.sjherp.app.security.CurrentUser;
import java.time.Duration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/developer-agent/tasks")
@PreAuthorize("@perm.has('developer:agent')")
public class DeveloperAgentController {
    private final DeveloperAgentService service;
    public DeveloperAgentController(DeveloperAgentService service){this.service=service;}
    @PostMapping("/from-candidate/{candidateId}") public Object start(@PathVariable long candidateId){return service.start(candidateId,CurrentUser.operator());}
    @GetMapping("/{id}") public Object get(@PathVariable long id){return service.get(id);}
    @PostMapping("/{id}/run") public Object run(@PathVariable long id){return service.run(id,CurrentUser.operator());}
    @PostMapping("/{id}/approve") public Object approve(@PathVariable long id){return service.approve(id,CurrentUser.operator());}
    @PostMapping("/{id}/cancel") public Object cancel(@PathVariable long id){return service.cancel(id,CurrentUser.operator());}
    @PostMapping("/reclaim-expired") public int reclaim(){return service.reclaimExpired(Duration.ofMinutes(10));}
}
