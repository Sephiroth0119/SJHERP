package com.sjherp.infra.github;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.GitHubIssueClient;

public final class RestGitHubIssueClient implements GitHubIssueClient {
    private final HttpClient http; private final ObjectMapper json; private final String base, repo, token; private final Duration timeout;
    public RestGitHubIssueClient(String base,String repo,String token,Duration timeout,ObjectMapper json){
        if(base.isBlank()||repo.isBlank()||token.isBlank()) throw new IllegalStateException("GitHub 配置缺失，外部写入已安全关闭");
        this.base=base.replaceAll("/$",""); this.repo=repo; this.token=token; this.json=json; this.timeout=timeout;
        this.http=HttpClient.newBuilder().connectTimeout(timeout).build();
    }
    public IssueResponse create(IssueRequest r){
        try { String body=json.writeValueAsString(java.util.Map.of("title",r.title(),"body",r.body(),"labels",r.labels()));
            var response=http.send(request("/repos/"+repo+"/issues").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()/100!=2) throw new IllegalStateException("GitHub 创建 Issue 失败，HTTP "+response.statusCode());
            JsonNode n=json.readTree(response.body()); return new IssueResponse(n.get("number").asLong(),n.get("html_url").asText());
        } catch(InterruptedException e){Thread.currentThread().interrupt(); throw new IllegalStateException("GitHub 请求被中断",e);}
        catch(Exception e){if(e instanceof IllegalStateException x) throw x; throw new IllegalStateException("GitHub 请求失败",e);}
    }
    public Optional<IssueResponse> findByTraceMarker(String marker){
        try { var response=http.send(request("/search/issues?q=repo:"+repo+"+"+java.net.URLEncoder.encode(marker,java.nio.charset.StandardCharsets.UTF_8)).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()/100!=2) throw new IllegalStateException("GitHub 查询 Issue 失败，HTTP "+response.statusCode());
            JsonNode root=json.readTree(response.body());
            if(!root.isObject() || !root.has("items") || !root.get("items").isArray()) throw new IllegalStateException("GitHub 查询响应缺少 items");
            for(JsonNode n:root.get("items")) {
                if(n.path("body").asText().contains(marker)) {
                    if(!n.hasNonNull("number") || !n.hasNonNull("html_url")) throw new IllegalStateException("GitHub Issue 响应缺少字段");
                    return Optional.of(new IssueResponse(n.get("number").asLong(),n.get("html_url").asText()));
                }
            }
            return Optional.empty();
        } catch(InterruptedException e){Thread.currentThread().interrupt(); throw new IllegalStateException("GitHub 查询被中断",e);}
        catch(Exception e){if(e instanceof IllegalStateException x) throw x; throw new IllegalStateException("GitHub 查询失败",e);}
    }
    private HttpRequest.Builder request(String path){return HttpRequest.newBuilder(URI.create(base+path)).timeout(timeout).header("Authorization","Bearer "+token).header("User-Agent","SJHERP-gap-issue").header("X-GitHub-Api-Version","2022-11-28").header("Accept","application/vnd.github+json").header("Content-Type","application/json");}
}
