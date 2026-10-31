package com.nutrimarket.nutrimarket.integration.http;

import com.nutrimarket.nutrimarket.exception.AgentException;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProviderHttp {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper;
    public ProviderHttp(ObjectMapper mapper) { this.mapper=mapper; }
    public JsonNode json(String method, String url, String key, Duration timeout, Object body, String service) {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Accept","application/json");
        if (!key.isBlank()) request.header("Authorization", "Bearer "+key);
        if (method.equals("POST")) request.header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        else request.GET();
        try {
            var response=client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200 || response.statusCode()>=300)
                throw AgentException.provider(service+"_HTTP_"+response.statusCode(), service+"  service request failed. Check the configuration or try again later.");
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AgentException.provider(service+"_INTERRUPTED", " service request was interrupted. Please try again later.");
        } catch (IOException e) {
            throw AgentException.provider(service+"_UNAVAILABLE", service+"  service is temporarily unavailable. Please try again later.");
        } catch (tools.jackson.core.JacksonException e) {
            throw AgentException.provider(service+"_INVALID_RESPONSE", service+"  service returned invalid data.");
        }
    }
}
