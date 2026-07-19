package com.sjherp.app.gap;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import org.junit.jupiter.api.Test;

class RestDeveloperAgentRunnerTest {
    @Test void postsLockedCandidateAndParsesQualityEvidence() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/v1/developer-tasks", exchange -> {String body=new String(exchange.getRequestBody().readAllBytes());assertThat(body).contains("missing","sourceGapNos","issueNumber");byte[] response="{\"artifacts\":[\"code.java\",\"test.java\"],\"targetedTestsGreen\":true,\"fullTestsGreen\":true,\"ciGreen\":true,\"ciEvidence\":\"ci://1\",\"outputSummary\":\"ok\"}".getBytes();exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});
        server.start();
        try {RestDeveloperAgentRunner runner=new RestDeveloperAgentRunner("http://localhost:"+server.getAddress().getPort(),"token",java.time.Duration.ofSeconds(3),new ObjectMapper());DeveloperAgentRunner.Result result=runner.run(new DeveloperAgentRunner.RunRequest(task(),candidate()));assertThat(result.ciGreen()).isTrue();assertThat(result.generatedArtifacts()).containsExactly("code.java","test.java");} finally {server.stop(0);}
    }
    private DeveloperAgentTask task(){return new DeveloperAgentTask(1,2,"k",DeveloperAgentTaskStatus.RUNNING,"codex/dev/k","C:/repo/k","REST","lease",1,List.of(),false,false,false,null,false);}
    private GapIssueCandidate candidate(){return new GapIssueCandidate(2,"k","k",BusinessModule.GENERAL,GapSeverity.LOW,"title",List.of("scenario"),"expected","missing",List.of("GAP-1"),GapIssueStatus.SENT,7L,"url",null,null,null,0,null,null,null);}
}
