package com.sjherp.domain.gap;

public interface DeveloperAgentRunner {
    default String kind(){return getClass().getSimpleName();}
    default boolean available(){return true;}
    record RunRequest(DeveloperAgentTask task, GapIssueCandidate candidate) { }
    record Result(java.util.List<String> generatedArtifacts, boolean targetedTestsGreen,
                  boolean fullTestsGreen, boolean ciGreen, String ciEvidence, String outputSummary) {
        public Result { generatedArtifacts = java.util.List.copyOf(generatedArtifacts); }
    }
    Result run(RunRequest request);
}
