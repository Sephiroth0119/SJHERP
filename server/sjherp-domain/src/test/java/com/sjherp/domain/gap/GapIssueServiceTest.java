package com.sjherp.domain.gap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.sjherp.domain.common.PageResult;

class GapIssueServiceTest {

    @Test
    void clusterScansMoreThanOnePageAndKeepsTwentyStableSamples() {
        FakeGaps gaps = new FakeGaps();
        for (int index = 0; index < 203; index++) {
            gaps.rows.add(gap("GAP-E-" + index, "Export", "export scenario " + index));
        }
        gaps.rows.add(gap("GAP-I-1", "Import", "import scenario 1"));
        gaps.rows.add(gap("GAP-I-2", "Import", "import scenario 2"));
        FakeCandidates candidates = new FakeCandidates();

        List<GapIssueCandidate> clustered = service(gaps, candidates, new RecordingClient(), false,
                new RecordingFinalizer(candidates)).cluster("reviewer");

        assertThat(clustered).hasSize(2);
        GapIssueCandidate export = clustered.stream()
                .filter(candidate -> candidate.title().equals("Export"))
                .findFirst()
                .orElseThrow();
        assertThat(export.scenarioSamples()).hasSize(20).startsWith("export scenario 0");
        assertThat(gaps.pages).isEqualTo(2);
    }

    @Test
    void disabledDeliveryDoesNotCallGateway() {
        FakeCandidates candidates = new FakeCandidates();
        candidates.value = candidate(1, GapIssueStatus.APPROVED, List.of("GAP-1"), List.of("scenario"));
        RecordingClient client = new RecordingClient();

        assertThatThrownBy(() -> service(new FakeGaps(), candidates, client, false,
                new RecordingFinalizer(candidates)).deliver(1, "reviewer"))
                .isInstanceOf(GapIssueDisabledException.class);
        assertThat(client.created).isFalse();
    }

    @Test
    void approveMissingCandidateUsesTypedNotFoundException() {
        FakeCandidates candidates = new FakeCandidates();

        assertThatThrownBy(() -> service(new FakeGaps(), candidates, new RecordingClient(), true,
                new RecordingFinalizer(candidates)).approve(404, "reviewer"))
                .isInstanceOf(GapIssueNotFoundException.class);
    }

    @Test
    void deliverCreatesIssueFromSnapshotReloadedAfterClaim() {
        FakeCandidates candidates = new FakeCandidates();
        candidates.value = candidate(7, GapIssueStatus.APPROVED, List.of("GAP-1"), List.of("scenario one"));
        RecordingClient client = new RecordingClient();
        RecordingFinalizer finalizer = new RecordingFinalizer(candidates);
        finalizer.afterClaim = () -> candidates.value = candidate(7, GapIssueStatus.SENDING,
                List.of("GAP-1", "GAP-2"), List.of("scenario one", "scenario two"));

        service(new FakeGaps(), candidates, client, true, finalizer).deliver(7, "reviewer");

        assertThat(client.created).isTrue();
        assertThat(client.lastRequest.body()).contains("scenario two", "GAP-2");
        assertThat(finalizer.finalized.sourceGapNos()).containsExactly("GAP-1", "GAP-2");
        assertThat(candidates.value.status()).isEqualTo(GapIssueStatus.SENT);
    }

    @Test
    void deliverRecoversExistingIssueByTraceWithoutCreatingAnother() {
        FakeCandidates candidates = new FakeCandidates();
        candidates.value = candidate(8, GapIssueStatus.APPROVED, List.of("GAP-8"), List.of("scenario"));
        RecordingClient client = new RecordingClient();
        client.trace = Optional.of(response(88));
        RecordingFinalizer finalizer = new RecordingFinalizer(candidates);

        service(new FakeGaps(), candidates, client, true, finalizer).deliver(8, "reviewer");

        assertThat(client.created).isFalse();
        assertThat(finalizer.finalizedIssueNumber).isEqualTo(88);
    }

    @Test
    void missingRequiredLabelsFailsDeliveryAndRecordsFailure() {
        FakeCandidates candidates = new FakeCandidates();
        candidates.value = candidate(9, GapIssueStatus.APPROVED, List.of("GAP-9"), List.of("scenario"));
        RecordingClient client = new RecordingClient();
        client.createResponse = new GitHubIssueClient.IssueResponse(9, "https://example.test/9",
                List.of("sjherp-gap", "general"));
        RecordingFinalizer finalizer = new RecordingFinalizer(candidates);

        assertThatThrownBy(() -> service(new FakeGaps(), candidates, client, true, finalizer)
                .deliver(9, "reviewer"))
                .isInstanceOf(GitHubIssueGatewayException.class);
        assertThat(finalizer.failureType).isEqualTo(GitHubIssueGatewayException.class.getSimpleName());
        assertThat(finalizer.finalized).isNull();
    }

    @Test
    void gatewayFailureRecordsFailureThroughFinalizer() {
        FakeCandidates candidates = new FakeCandidates();
        candidates.value = candidate(10, GapIssueStatus.APPROVED, List.of("GAP-10"), List.of("scenario"));
        RecordingClient client = new RecordingClient();
        client.createFailure = new GitHubIssueGatewayException("remote unavailable");
        RecordingFinalizer finalizer = new RecordingFinalizer(candidates);

        assertThatThrownBy(() -> service(new FakeGaps(), candidates, client, true, finalizer)
                .deliver(10, "reviewer"))
                .isInstanceOf(GitHubIssueGatewayException.class);
        assertThat(finalizer.failureType).isEqualTo(GitHubIssueGatewayException.class.getSimpleName());
    }

    private static GapIssueService service(
            GapRecordRepository gaps,
            FakeCandidates candidates,
            GitHubIssueClient client,
            boolean enabled,
            GapIssueDeliveryFinalizer finalizer) {
        return new GapIssueService(gaps, candidates, client, enabled, finalizer,
                (candidate, sources, operator) -> {
                    candidates.value = candidate;
                    return candidate;
                });
    }

    private static GapRecord gap(String gapNo, String title, String scenario) {
        return new GapRecord(gapNo, null, title, scenario, "expected", "missing",
                BusinessModule.GENERAL, GapSeverity.LOW, "reporter", "creator");
    }

    private static GapIssueCandidate candidate(
            long id,
            GapIssueStatus status,
            List<String> sources,
            List<String> scenarios) {
        return new GapIssueCandidate(id, "key-" + id, "key-" + id,
                BusinessModule.GENERAL, GapSeverity.LOW, "Export", scenarios,
                "expected", "missing", sources, status, null, null, null, null, null,
                0, Instant.now(), Instant.now(), null);
    }

    private static GitHubIssueClient.IssueResponse response(long number) {
        return new GitHubIssueClient.IssueResponse(number, "https://example.test/" + number,
                List.of("sjherp-gap", "general", "low"));
    }

    private static GapIssueCandidate sent(GapIssueCandidate candidate, long number, String url) {
        return new GapIssueCandidate(candidate.id(), candidate.idempotencyKey(), candidate.clusterKey(),
                candidate.businessModule(), candidate.severity(), candidate.title(), candidate.scenarioSamples(),
                candidate.expectedBehavior(), candidate.missingCapability(), candidate.sourceGapNos(),
                GapIssueStatus.SENT, number, url, candidate.reviewedBy(), candidate.reviewedAt(), null,
                candidate.attemptCount(), candidate.createdAt(), Instant.now(), null);
    }

    static final class FakeGaps implements GapRecordRepository {
        final List<GapRecord> rows = new ArrayList<>();
        int pages;

        @Override
        public void save(GapRecord record) {
        }

        @Override
        public Optional<GapRecord> findById(long id) {
            return Optional.empty();
        }

        @Override
        public Optional<GapRecord> findByGapNo(String gapNo) {
            return rows.stream().filter(row -> row.getGapNo().equals(gapNo)).findFirst();
        }

        @Override
        public PageResult<GapRecord> search(GapRecordQuery query) {
            pages++;
            int from = (query.page() - 1) * query.size();
            if (from >= rows.size()) {
                return new PageResult<>(List.of(), rows.size(), query.page(), query.size());
            }
            int to = Math.min(from + query.size(), rows.size());
            return new PageResult<>(rows.subList(from, to), rows.size(), query.page(), query.size());
        }
    }

    static final class FakeCandidates implements GapIssueCandidateRepository {
        GapIssueCandidate value;

        @Override
        public GapIssueCandidate upsert(GapIssueCandidate candidate) {
            value = candidate;
            return value;
        }

        @Override
        public void addSources(long candidateId, List<String> gapNos) {
        }

        @Override
        public List<GapIssueCandidate> findAll() {
            return value == null ? List.of() : List.of(value);
        }

        @Override
        public List<GapIssueCandidate> findDispatchable(int maxAttempts, int limit) {
            return findAll();
        }

        @Override
        public Optional<GapIssueCandidate> findById(long id) {
            return value != null && value.id() == id ? Optional.of(value) : Optional.empty();
        }

        @Override
        public Optional<String> claimForSend(long id) {
            return Optional.of("lease-" + id);
        }

        @Override
        public int reclaimExpiredSending(Instant cutoff) {
            return 0;
        }

        @Override
        public void markApproved(long id, String operator) {
        }

        @Override
        public void markSent(long id, String leaseToken, long number, String url) {
        }

        @Override
        public void markFailed(long id, String leaseToken, String failureType) {
        }
    }

    static final class RecordingFinalizer implements GapIssueDeliveryFinalizer {
        private final FakeCandidates candidates;
        Runnable afterClaim = () -> { };
        GapIssueCandidate finalized;
        Long finalizedIssueNumber;
        String failureType;

        RecordingFinalizer(FakeCandidates candidates) {
            this.candidates = candidates;
        }

        @Override
        public Optional<String> claimDelivery(long candidateId, String operator) {
            afterClaim.run();
            return Optional.of("lease-" + candidateId);
        }

        @Override
        public void finalizeDelivery(
                GapIssueCandidate candidate,
                String leaseToken,
                long number,
                String url,
                String operator) {
            finalized = candidate;
            finalizedIssueNumber = number;
            candidates.value = sent(candidate, number, url);
        }

        @Override
        public void failDelivery(long candidateId, String leaseToken, String failureType, String operator) {
            this.failureType = failureType;
        }
    }

    static final class RecordingClient implements GitHubIssueClient {
        boolean created;
        IssueRequest lastRequest;
        Optional<IssueResponse> trace = Optional.empty();
        IssueResponse createResponse = response(1);
        RuntimeException createFailure;

        @Override
        public IssueResponse create(IssueRequest request) {
            created = true;
            lastRequest = request;
            if (createFailure != null) {
                throw createFailure;
            }
            return createResponse;
        }

        @Override
        public Optional<IssueResponse> findByTraceMarker(String marker) {
            return trace;
        }
    }
}
