package com.sjherp.app.gap;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
class WorkspaceCommandPolicyTest {
 @Test void rejectsWorkspaceEscapeAndInvalidBranch(){WorkspacePolicy p=new WorkspacePolicy(Path.of("."));org.assertj.core.api.Assertions.assertThat(p.validate("codex/dev/x",Path.of("nested","not-yet-created"))).isAbsolute();assertThatThrownBy(()->p.validate("codex/dev/x",Path.of("..","secret"))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->p.validate("main",Path.of("nested"))).isInstanceOf(IllegalArgumentException.class);}
 @Test void rejectsDangerousOrNonAllowlistedCommands(){CommandPolicy p=new CommandPolicy(Set.of(List.of("mvn","test")));assertThatThrownBy(()->p.validate(List.of("git","merge","main"),Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->p.validate(List.of("mvn","verify"),Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);}
 @Test void truncatesOutputAtConfiguredBoundary(){org.assertj.core.api.Assertions.assertThat(CommandPolicy.truncateOutput("abcdef",3)).isEqualTo("abc");}
}
