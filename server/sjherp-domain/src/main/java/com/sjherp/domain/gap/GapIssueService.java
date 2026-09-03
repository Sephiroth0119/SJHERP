package com.sjherp.domain.gap;

import com.sjherp.domain.common.audit.Audited;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class GapIssueService {
    private static final int CLUSTER_PAGE_SIZE = 200;
    private static final int MAX_SCENARIO_SAMPLES = 20;

    private final GapRecordRepository gaps;
    private final GapIssueCandidateRepository candidates;
    private final GitHubIssueClient github;
    private final GapIssueDeliveryFinalizer finalizer;
    private final GapIssueClusterWriter writer;
    private final boolean enabled;

    public GapIssueService(
            GapRecordRepository gaps,
            GapIssueCandidateRepository candidates,
            GitHubIssueClient github,
            boolean enabled,
            GapIssueDeliveryFinalizer finalizer,
            GapIssueClusterWriter writer) {
        this.gaps = Objects.requireNonNull(gaps);
        this.candidates = Objects.requireNonNull(candidates);
        this.github = Objects.requireNonNull(github);
        this.enabled = enabled;
        this.finalizer = Objects.requireNonNull(finalizer);
        this.writer = Objects.requireNonNull(writer);
    }

    @Audited(action = "gap.issue.cluster", targetType = "gap_issue")
    public List<GapIssueCandidate> cluster(String operator) {
        List<GapRecord> all = new ArrayList<>();
        for (int page = 1; ; page++) {
            var result = gaps.search(new GapRecordQuery(GapStatus.NEW, null, page, CLUSTER_PAGE_SIZE));
            all.addAll(result.items());
            if (result.items().isEmpty() || all.size() >= result.total()) {
                break;
            }
        }

        Map<String, List<GapRecord>> groups = new LinkedHashMap<>();
        for (GapRecord gap : all) {
            groups.computeIfAbsent(clusterKey(gap), ignored -> new ArrayList<>()).add(gap);
        }

        List<GapIssueCandidate> result = new ArrayList<>();
        for (List<GapRecord> rows : groups.values()) {
            rows.sort(Comparator
                    .comparing(GapRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(GapRecord::getId, Comparator.nullsLast(Comparator.naturalOrder())));
            GapRecord first = rows.getFirst();
            List<String> sources = rows.stream().map(GapRecord::getGapNo).toList();
            GapIssueCandidate candidate = new GapIssueCandidate(
                    0,
                    clusterKey(first),
                    clusterKey(first),
                    first.getBusinessModule(),
                    first.getSeverity(),
                    first.getTitle(),
                    rows.stream().map(GapRecord::getScenario).limit(MAX_SCENARIO_SAMPLES).toList(),
                    first.getExpectedBehavior(),
                    first.getMissingCapability(),
                    sources,
                    GapIssueStatus.PENDING,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    null,
                    null,
                    null);
            result.add(writer.write(candidate, sources, operator));
        }
        return result;
    }

    public List<GapIssueCandidate> list() {
        return candidates.findAll();
    }

    public List<GapIssueCandidate> dispatchable(int maxAttempts, int limit) {
        return candidates.findDispatchable(maxAttempts, limit);
    }

    @Audited(action = "gap.issue.reclaim", targetType = "gap_issue")
    public int reclaimExpiredSending(Duration lease, String operator) {
        return candidates.reclaimExpiredSending(Instant.now().minus(lease));
    }

    @Audited(action = "gap.issue.review", targetType = "gap_issue")
    public GapIssueCandidate approve(long id, String operator) {
        if (candidates.findById(id).isEmpty()) {
            throw new GapIssueNotFoundException(id);
        }
        candidates.markApproved(id, operator);
        return candidates.findById(id).orElseThrow(() -> new GapIssueNotFoundException(id));
    }

    @Audited(action = "gap.issue.deliver", targetType = "gap_issue")
    public GapIssueCandidate deliver(long id, String operator) {
        if (!enabled) {
            throw new GapIssueDisabledException();
        }

        GapIssueCandidate candidate = candidates.findById(id)
                .orElseThrow(() -> new GapIssueNotFoundException(id));
        if (candidate.status() != GapIssueStatus.APPROVED && candidate.status() != GapIssueStatus.FAILED) {
            throw new GapIssueStateException("candidate is not approved");
        }
        if (candidate.issueNumber() != null) {
            return candidate;
        }

        Optional<String> lease = finalizer.claimDelivery(id, operator);
        if (lease.isEmpty()) {
            return candidates.findById(id).orElseThrow(() -> new GapIssueNotFoundException(id));
        }

        // A concurrent cluster pass may have merged sources and scenarios between the
        // first read above and the successful claim. Build the remote payload from the
        // claimed SENDING snapshot; arrivals after this point are reconciled by the
        // finalizer's fresh source reload.
        GapIssueCandidate claimedCandidate = candidates.findById(id)
                .orElseThrow(() -> new GapIssueNotFoundException(id));
        try {
            String marker = "SJHERP-GAP-TRACE:" + claimedCandidate.idempotencyKey();
            GitHubIssueClient.IssueResponse issue = github.findByTraceMarker(marker)
                    .orElseGet(() -> github.create(issueRequest(claimedCandidate, marker)));
            verifyLabels(claimedCandidate, issue);
            finalizer.finalizeDelivery(claimedCandidate, lease.get(), issue.number(), issue.url(), operator);
        } catch (RuntimeException exception) {
            failDeliveryWithoutMaskingOriginal(id, lease.get(), exception, operator);
            if (exception instanceof GapIssueStateException stateException) {
                throw stateException;
            }
            if (exception instanceof GapIssueDisabledException disabledException) {
                throw disabledException;
            }
            if (exception instanceof GitHubIssueGatewayException gatewayException) {
                throw gatewayException;
            }
            throw new GitHubIssueGatewayException(exception);
        }
        return candidates.findById(id).orElseThrow(() -> new GapIssueNotFoundException(id));
    }

    static String clusterKey(GapRecord gap) {
        String value = normalize(gap.getBusinessModule().name()) + "|"
                + normalize(gap.getSeverity().name()) + "|"
                + normalize(gap.getTitle()) + "|"
                + normalize(gap.getMissingCapability()) + "|"
                + normalize(gap.getExpectedBehavior());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte byteValue : digest) {
                result.append(String.format("%02x", byteValue));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("cannot calculate cluster key", exception);
        }
    }

    private GitHubIssueClient.IssueRequest issueRequest(GapIssueCandidate candidate, String marker) {
        return new GitHubIssueClient.IssueRequest(
                "[SJHERP][" + candidate.businessModule() + "][" + candidate.severity() + "] " + candidate.title(),
                List.of(
                        "sjherp-gap",
                        candidate.businessModule().name().toLowerCase(Locale.ROOT),
                        candidate.severity().name().toLowerCase(Locale.ROOT)),
                body(candidate) + "\n\n" + marker);
    }

    private void failDeliveryWithoutMaskingOriginal(
            long candidateId,
            String leaseToken,
            RuntimeException original,
            String operator) {
        try {
            finalizer.failDelivery(candidateId, leaseToken, original.getClass().getSimpleName(), operator);
        } catch (RuntimeException failure) {
            original.addSuppressed(failure);
        }
    }

    private static void verifyLabels(GapIssueCandidate candidate, GitHubIssueClient.IssueResponse issue) {
        Set<String> actual = issue.labels().stream()
                .map(label -> label.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        Set<String> required = Set.of(
                "sjherp-gap",
                candidate.businessModule().name().toLowerCase(Locale.ROOT),
                candidate.severity().name().toLowerCase(Locale.ROOT));
        if (!actual.containsAll(required)) {
            throw new GitHubIssueGatewayException(
                    new IllegalStateException("GitHub Issue labels were not retained"));
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String body(GapIssueCandidate candidate) {
        return "## Scenario\n"
                + candidate.scenarioSamples().stream().collect(Collectors.joining("\n- ", "- ", ""))
                + "\n\n## Expected behavior\n"
                + candidate.expectedBehavior()
                + "\n\n## Missing capability\n"
                + candidate.missingCapability()
                + "\n\n## Sources\n"
                + String.join(", ", candidate.sourceGapNos())
                + "\n\nTrace: `"
                + candidate.idempotencyKey()
                + "`";
    }
}
