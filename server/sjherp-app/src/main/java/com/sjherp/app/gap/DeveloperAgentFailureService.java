package com.sjherp.app.gap;

import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.gap.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeveloperAgentFailureService {
    private final DeveloperAgentTaskRepository tasks;

    public DeveloperAgentFailureService(DeveloperAgentTaskRepository tasks) {
        this.tasks = tasks;
    }

    @Transactional
    @Audited(action = "developer.task.fail", targetType = "developer_task")
    public DeveloperAgentTask fail(long id, DeveloperAgentTaskStatus expected, String lease,
                                    String type, String summary, List<String> artifacts,
                                    boolean targeted, boolean full, boolean ci,
                                    String evidence, String output, String operator) {
        tasks.markFailed(id, expected, lease, type, summary, artifacts, targeted, full, ci, evidence, output);
        return get(id);
    }

    public DeveloperAgentTask get(long id) {
        return tasks.findById(id).orElseThrow(() -> new DeveloperAgentTaskNotFoundException(id));
    }
}
