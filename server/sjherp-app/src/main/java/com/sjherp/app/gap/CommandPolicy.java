package com.sjherp.app.gap;
import java.time.Duration;
import java.util.*;
public final class CommandPolicy {
    private final Set<List<String>> allowed;
    public CommandPolicy(Set<List<String>> allowed){this.allowed=Set.copyOf(allowed);}
    public void validate(List<String> argv, Duration timeout){
        if(argv==null||argv.isEmpty()||!allowed.contains(List.copyOf(argv))) throw new IllegalArgumentException("command is not allowlisted");
        String joined=String.join(" ",argv).toLowerCase(Locale.ROOT);
        if(joined.contains("merge")||joined.contains("deploy")||joined.contains(" rm")||joined.contains("reset --hard")||joined.contains("checkout .")) throw new IllegalArgumentException("dangerous command rejected");
        if(timeout==null||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofMinutes(10))>0) throw new IllegalArgumentException("invalid command timeout");
    }
    public static String truncateOutput(String output, int maxChars){
        if(maxChars<0) throw new IllegalArgumentException("maxChars must not be negative");
        if(output==null||output.length()<=maxChars)return output;
        return output.substring(0,maxChars);
    }
}
