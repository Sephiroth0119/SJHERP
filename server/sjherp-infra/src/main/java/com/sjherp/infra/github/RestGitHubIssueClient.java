package com.sjherp.infra.github;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.GitHubIssueClient;

public final class RestGitHubIssueClient implements GitHubIssueClient {
    private final HttpClient http; private final ObjectMapper json; private final String base, repo, token;
    public RestGitHubIssueClient(String base,String repo,String token,Duration timeout,ObjectMapper json){
        if(base.isBlank()||repo.isBlank()||token.isBlank()) throw new IllegalStateException("GitHub 配置缺失，外部写入已安全关闭");
        this.base=base.replaceAll("/$",""); this.repo=repo; this.token=token; this.json=json;
        this.http=HttpClient.newBuilder().connectTimeout(timeout).build();
    }
    public IssueResponse create(IssueRequest r){
        try { String body=json.writeValueAsString(java.util.Map.of("title",r.title(),"body",r.body(),"labels",r.labels()));
            var response=http.send(request("/repos/"+repo+"/issues").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()/100!=2) throw new IllegalStateException("GitHub 创建 Issue 失败，HTTP "+response.statusCode());
            JsonNode n=json.readTree(response.body()); return new IssueResponse(n.get("number").asLong(),n.get("html_url").asText());
        } catch(Exception e){if(e instanceof IllegalStateException x) throw x; throw new IllegalStateException("GitHub 请求失败",e);}
    }
    public Optional<IssueResponse> findByTraceMarker(String marker){
        try { var response=http.send(request("/repos/"+repo+"/issues?state=all&per_page=100").GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()/100!=2) throw new IllegalStateException("GitHub 查询 Issue 失败，HTTP "+response.statusCode());
            for(JsonNode n:json.readTree(response.body())) if(n.path("body").asText().contains(marker)) return Optional.of(new IssueResponse(n.get("number").asLong(),n.get("html_url").asText()));
            return Optional.empty();
        } catch(Exception e){if(e instanceof IllegalStateException x) throw x; throw new IllegalStateException("GitHub 查询失败",e);}
    }
    private HttpRequest.Builder request(String path){return HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+token).header("Accept","application/vnd.github+json").header("Content-Type","application/json");}
}
