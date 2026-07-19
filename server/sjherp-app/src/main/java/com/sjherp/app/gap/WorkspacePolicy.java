package com.sjherp.app.gap;
import java.nio.file.*;
import java.util.regex.Pattern;
import java.util.Optional;
public final class WorkspacePolicy {
    private static final Pattern BRANCH=Pattern.compile("codex/dev/[a-zA-Z0-9._-]{1,100}");
    private final Path repositoryRoot;
    public WorkspacePolicy(Path repositoryRoot){this.repositoryRoot=repositoryRoot.toAbsolutePath().normalize();}
    public Path validate(String branch, Path workspace){
        if(!BRANCH.matcher(branch).matches()) throw new IllegalArgumentException("invalid isolated branch");
        Path p=workspace.toAbsolutePath().normalize();
        if(!p.startsWith(repositoryRoot) || p.equals(repositoryRoot)) throw new IllegalArgumentException("workspace escapes repository allowlist");
        try { Path parent=Files.exists(p)?p:Optional.ofNullable(p.getParent()).orElseThrow(); Path realParent=parent.toRealPath(); if(!realParent.startsWith(repositoryRoot.toRealPath())) throw new IllegalArgumentException("workspace real path escapes allowlist"); } catch(java.io.IOException e){throw new IllegalArgumentException("workspace path cannot be verified",e);}
        return p;
    }
}
