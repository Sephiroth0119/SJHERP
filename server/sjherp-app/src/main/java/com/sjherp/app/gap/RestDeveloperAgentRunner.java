package com.sjherp.app.gap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** 受控外部 runner 端口；只发送已锁定候选快照，默认由配置关闭。 */
public final class RestDeveloperAgentRunner implements DeveloperAgentRunner {
    private final URI endpoint; private final String token; private final Duration timeout; private final ObjectMapper json;
    public RestDeveloperAgentRunner(String baseUrl,String token,Duration timeout,ObjectMapper json){this.endpoint=URI.create(baseUrl.endsWith("/")?baseUrl+"v1/developer-tasks":baseUrl+"/v1/developer-tasks");this.token=token;this.timeout=timeout;this.json=json;}
    @Override public String kind(){return "REST";}
    @Override public Result run(RunRequest request){try{Map<String,Object> c=Map.of("candidateId",request.candidate().id(),"issueNumber",request.candidate().issueNumber(),"title",request.candidate().title(),"scenarios",request.candidate().scenarioSamples(),"expectedBehavior",request.candidate().expectedBehavior(),"missingCapability",request.candidate().missingCapability(),"sourceGapNos",request.candidate().sourceGapNos());String body=json.writeValueAsString(Map.of("taskId",request.task().id(),"branchName",request.task().branchName(),"workspacePath",request.task().workspacePath(),"candidate",c));HttpRequest http=HttpRequest.newBuilder(endpoint).timeout(timeout).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();HttpResponse<String> response=HttpClient.newHttpClient().send(http,HttpResponse.BodyHandlers.ofString());if(response.statusCode()/100!=2)throw new GitHubIssueGatewayException("developer runner returned "+response.statusCode());JsonNode n=json.readTree(response.body());if(!n.has("artifacts")||!n.has("targetedTestsGreen")||!n.has("fullTestsGreen")||!n.has("ciGreen")||!n.has("ciEvidence"))throw new GitHubIssueGatewayException("developer runner response missing quality evidence");List<String> artifacts=json.convertValue(n.get("artifacts"),json.getTypeFactory().constructCollectionType(List.class,String.class));return new Result(artifacts,n.get("targetedTestsGreen").asBoolean(),n.get("fullTestsGreen").asBoolean(),n.get("ciGreen").asBoolean(),n.get("ciEvidence").isNull()?null:n.get("ciEvidence").asText(),n.has("outputSummary")?n.get("outputSummary").asText():null);}catch(GitHubIssueGatewayException e){throw e;}catch(Exception e){throw new GitHubIssueGatewayException(e);}}
}
