package com.sjherp.app.consistency;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.notification.InAppNotificationChannel;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyCheckRunRepository;

/** 在独立事务中只追加运行报告，并同步产生站内通知。 */
@Service
public class ConsistencyRunPersistenceService {

    private final ConsistencyCheckRunRepository repository;
    private final InAppNotificationChannel inAppNotificationChannel;

    public ConsistencyRunPersistenceService(ConsistencyCheckRunRepository repository,
                                            InAppNotificationChannel inAppNotificationChannel) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.inAppNotificationChannel = Objects.requireNonNull(
                inAppNotificationChannel, "inAppNotificationChannel 不能为空");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(ConsistencyCheckRun run) {
        repository.save(Objects.requireNonNull(run, "run 不能为空"));
        inAppNotificationChannel.send(run);
    }
}
