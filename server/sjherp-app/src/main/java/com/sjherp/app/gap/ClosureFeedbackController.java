package com.sjherp.app.gap;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.gap.ClosureEvidence;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
@RestController
@RequestMapping("/api/developer-agent/tasks")
@PreAuthorize("@perm.has('developer:agent')")
public class ClosureFeedbackController {
 private final ClosureFeedbackService service; public ClosureFeedbackController(ClosureFeedbackService service){this.service=service;}
 public record Request(@NotBlank @Size(max=500) String reference, @NotBlank @Size(max=2000) String summary){}
 @PostMapping("/{id}/confirm-resolution") public void confirm(@PathVariable long id,@Valid @RequestBody Request request){service.confirm(id,new ClosureEvidence(request.reference(),request.summary()),CurrentUser.operator());}
}
