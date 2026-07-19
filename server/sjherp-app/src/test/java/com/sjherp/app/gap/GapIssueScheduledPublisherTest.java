package com.sjherp.app.gap;

import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.sjherp.domain.gap.*;

class GapIssueScheduledPublisherTest {
    @Test void 人工审核开启时保留PENDING但投递APPROVED和FAILED() {
        GapIssueService service=mock(GapIssueService.class);
        when(service.cluster()).thenReturn(List.of());
        GapIssueCandidate approved=candidate(1,GapIssueStatus.APPROVED,0);
        GapIssueCandidate failed=candidate(2,GapIssueStatus.FAILED,1);
        when(service.list()).thenReturn(List.of(approved,failed));
        new GapIssueScheduledPublisher(service,true,3).publish();
        verify(service).deliver(approved.id()); verify(service).deliver(failed.id());
    }
    @Test void 达到最大重试次数不再投递() {
        GapIssueService service=mock(GapIssueService.class); when(service.cluster()).thenReturn(List.of());
        GapIssueCandidate failed=candidate(1,GapIssueStatus.FAILED,3); when(service.list()).thenReturn(List.of(failed));
        new GapIssueScheduledPublisher(service,false,3).publish(); verify(service,never()).deliver(failed.id());
    }
    private static GapIssueCandidate candidate(long id,GapIssueStatus s,int attempts){return new GapIssueCandidate(id,"k"+id,"k"+id,BusinessModule.GENERAL,GapSeverity.LOW,"t",List.of("s"),"e","m",List.of("GAP-1"),s,null,null,null,null,"x",attempts,null,null,null);}
}
