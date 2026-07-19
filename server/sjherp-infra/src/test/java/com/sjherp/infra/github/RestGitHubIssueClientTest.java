package com.sjherp.infra.github;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import com.sjherp.domain.gap.GitHubIssueClient.IssueRequest;

class RestGitHubIssueClientTest {
    private HttpServer server;
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    @Test void createAndFindByTraceMarker使用结构化HTTP契约() throws Exception {
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/", exchange->{
            String body="POST".equals(exchange.getRequestMethod())?"{\"number\":7,\"html_url\":\"http://issue/7\"}":"{\"total_count\":1,\"items\":[{\"number\":7,\"html_url\":\"http://issue/7\",\"body\":\"SJHERP-GAP-TRACE:key\"}]}";
            exchange.sendResponseHeaders(200,body.getBytes().length); try(var out=exchange.getResponseBody()){out.write(body.getBytes());}
        }); server.start();
        var client=new RestGitHubIssueClient("http://localhost:"+server.getAddress().getPort(),"acme/demo","secret",Duration.ofSeconds(2),new ObjectMapper());
        assertThat(client.create(new IssueRequest("t",java.util.List.of("l"),"SJHERP-GAP-TRACE:key")).number()).isEqualTo(7);
        assertThat(client.findByTraceMarker("SJHERP-GAP-TRACE:key")).isPresent();
    }
}
