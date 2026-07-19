package com.sjherp.domain.gap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.InMemorySequenceProvider;

class GapIssueServiceTest {
    @Test void 分页聚类且幂等键确定() {
        FakeGaps gaps = new FakeGaps();
        gaps.rows.add(new GapRecord("GAP-1", null, " 导出 ", "场景", "期望", "能力", BusinessModule.GENERAL, GapSeverity.MEDIUM, "1", "1"));
        gaps.rows.add(new GapRecord("GAP-2", null, "导出", "场景2", "期望", "能力", BusinessModule.GENERAL, GapSeverity.MEDIUM, "1", "1"));
        FakeCandidates out = new FakeCandidates();
        var result = service(gaps, out, new SafeClient(), false).cluster("test");
        assertThat(result).hasSize(1); assertThat(result.get(0).scenarioSamples()).hasSize(2);
        assertThat(result.get(0).idempotencyKey()).isEqualTo(result.get(0).clusterKey());
        assertThat(gaps.pages).isEqualTo(1);
    }
    @Test void 外部关闭且不调用客户端() {
        FakeCandidates out = new FakeCandidates(); out.value = new GapIssueCandidate(1,"k","k",BusinessModule.GENERAL,GapSeverity.LOW,"t",List.of("s"),"e","m",List.of("GAP-1"),GapIssueStatus.APPROVED,null,null,null,null,null,0,null,null,null);
        var client = new SafeClient();
        assertThatThrownBy(() -> service(new FakeGaps(),out,client,false).deliver(1,"test")).isInstanceOf(GapIssueDisabledException.class);
        assertThat(client.created).isFalse();
    }
    static final class FakeGaps implements GapRecordRepository { final List<GapRecord> rows=new ArrayList<>(); int pages;
        public void save(GapRecord r){} public Optional<GapRecord> findById(long id){return Optional.empty();} public Optional<GapRecord> findByGapNo(String no){return Optional.empty();}
        public PageResult<GapRecord> search(GapRecordQuery q){pages++; int from=(q.page()-1)*q.size(); if(from>=rows.size()) return new PageResult<>(List.of(),rows.size(),q.page(),q.size()); return new PageResult<>(rows.subList(from,rows.size()),rows.size(),q.page(),q.size());}}
    static GapIssueService service(GapRecordRepository gaps, GapIssueCandidateRepository out, GitHubIssueClient client, boolean enabled) {
        return new GapIssueService(gaps, out, client, enabled, new GapIssueDeliveryFinalizer() {
            public Optional<String> claimDelivery(long id, String o) { return out.claimForSend(id); }
            public void finalizeDelivery(GapIssueCandidate c, String t, long n, String u, String o) { }
            public void failDelivery(long id, String t, String type, String o) { }
        }, (candidate, sources, operator) -> { out.upsert(candidate); return candidate; });
    }
    static GapRecordService gapService(GapRecordRepository gaps) { return new GapRecordService(gaps, new DefaultDocumentNumberGenerator(new InMemorySequenceProvider())); }
    static final class FakeCandidates implements GapIssueCandidateRepository { GapIssueCandidate value; public GapIssueCandidate upsert(GapIssueCandidate c){value=c;return value;} public void addSources(long id,List<String> n){} public int reclaimExpiredSending(java.time.Instant t){return 0;} public List<GapIssueCandidate> findAll(){return List.of(value);} public List<GapIssueCandidate> findDispatchable(int m,int l){return findAll();} public Optional<GapIssueCandidate> findById(long id){return Optional.ofNullable(value);} public Optional<String> claimForSend(long id){return Optional.of("lease");} public void markApproved(long id,String o){} public void markSent(long i,String t,long n,String u){} public void markFailed(long i,String t,String x){}}
    static final class SafeClient implements GitHubIssueClient { boolean created; public IssueResponse create(IssueRequest r){created=true; return new IssueResponse(1,"url",r.labels());} public Optional<IssueResponse> findByTraceMarker(String m){return Optional.empty();}}
}
