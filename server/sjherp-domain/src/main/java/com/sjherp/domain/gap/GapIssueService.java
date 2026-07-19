package com.sjherp.domain.gap;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.stream.Collectors;

import com.sjherp.domain.common.audit.Audited;

public class GapIssueService {
    private final GapRecordRepository gaps;
    private final GapIssueCandidateRepository candidates;
    private final GitHubIssueClient github;
    private final boolean enabled;
    public GapIssueService(GapRecordRepository gaps, GapIssueCandidateRepository candidates,
                           GitHubIssueClient github, boolean enabled) {
        this.gaps=Objects.requireNonNull(gaps); this.candidates=Objects.requireNonNull(candidates);
        this.github=Objects.requireNonNull(github); this.enabled=enabled;
    }
    @Audited(action="gap.issue.cluster", targetType="gap_issue")
    public List<GapIssueCandidate> cluster() {
        var all = new ArrayList<GapRecord>();
        int pageNo = 1;
        while (true) {
            var page = gaps.search(new GapRecordQuery(GapStatus.NEW, null, pageNo, 200));
            all.addAll(page.items());
            if (page.items().isEmpty() || all.size() >= page.total()) break;
            pageNo++;
        }
        var grouped=new LinkedHashMap<String,List<GapRecord>>();
        for (GapRecord gap: all) grouped.computeIfAbsent(clusterKey(gap), k->new ArrayList<>()).add(gap);
        var result=new ArrayList<GapIssueCandidate>();
        for (var e:grouped.entrySet()) {
            var rows=e.getValue(); var first=rows.get(0);
            var sources=rows.stream().map(GapRecord::getGapNo).toList();
            var c=new GapIssueCandidate(0,e.getKey(),e.getKey(),first.getBusinessModule(),first.getSeverity(),first.getTitle(),
                    rows.stream().map(GapRecord::getScenario).limit(20).toList(),first.getExpectedBehavior(),first.getMissingCapability(),sources,GapIssueStatus.PENDING,null,null);
            var saved = candidates.upsert(c);
            candidates.addSources(saved.id(), sources);
            result.add(saved);
        }
        return result;
    }
    public List<GapIssueCandidate> list() { return candidates.findAll(); }
    @Audited(action="gap.issue.review", targetType="gap_issue")
    public GapIssueCandidate approve(long id,String operator){ candidates.markApproved(id,operator); return candidates.findById(id).orElseThrow(); }
    @Audited(action="gap.issue.deliver", targetType="gap_issue")
    public GapIssueCandidate deliver(long id){
        if(!enabled) throw new IllegalStateException("GitHub Issue 外部写入已关闭");
        var c=candidates.findById(id).orElseThrow(); if(c.status()!=GapIssueStatus.APPROVED && c.status()!=GapIssueStatus.FAILED) throw new IllegalStateException("候选未审核通过");
        if(c.issueNumber()!=null) return c; if(!candidates.claimForSend(id)) return candidates.findById(id).orElseThrow();
        String marker = "SJHERP-GAP-TRACE:" + c.idempotencyKey();
        try { var existing = github.findByTraceMarker(marker);
            var r = existing.orElseGet(() -> github.create(new GitHubIssueClient.IssueRequest("[SJHERP]["+c.businessModule()+"]["+c.severity()+"] "+c.title(),List.of("sjherp-gap",c.businessModule().name().toLowerCase(Locale.ROOT),c.severity().name().toLowerCase(Locale.ROOT)),body(c)+"\n\n"+marker)));
            candidates.markSent(id,r.number(),r.url()); }
        catch(RuntimeException ex){ candidates.markFailed(id,ex.getClass().getSimpleName()); throw ex; }
        return candidates.findById(id).orElseThrow();
    }
    static String clusterKey(GapRecord g){
        String value = normalize(g.getBusinessModule().name()) + "|" + normalize(g.getSeverity().name()) + "|"
                + normalize(g.getTitle()) + "|" + normalize(g.getMissingCapability()) + "|"
                + normalize(g.getExpectedBehavior());
        try { var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var out = new StringBuilder(64); for (byte b : digest) out.append(String.format("%02x", b)); return out.toString();
        } catch (Exception e) { throw new IllegalStateException("无法计算缺口聚类键", e); }
    }
    private static String normalize(String s){return Normalizer.normalize(s,Normalizer.Form.NFKC).strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
    private static String body(GapIssueCandidate c){return "## 场景\n"+c.scenarioSamples().stream().collect(Collectors.joining("\n- ","- ",""))+"\n\n## 期望行为\n"+c.expectedBehavior()+"\n\n## 缺失能力\n"+c.missingCapability()+"\n\n## 来源\n"+String.join(", ",c.sourceGapNos())+"\n\n幂等键：`"+c.idempotencyKey()+"`";}
}
