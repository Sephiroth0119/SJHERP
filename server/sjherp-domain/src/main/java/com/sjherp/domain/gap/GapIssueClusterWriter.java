package com.sjherp.domain.gap;
import java.util.List;
public interface GapIssueClusterWriter { GapIssueCandidate write(GapIssueCandidate candidate, List<String> sourceGapNos, String operator); }
