package com.nutrimarket.nutrimarket.service.dietary;

import com.nutrimarket.nutrimarket.config.AgentConfiguration.Integrations;
import com.nutrimarket.nutrimarket.dao.dietary.IntakeOutboxMapper;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.service.health.HealthInbox;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Durable, bounded, at-least-once delivery. Agent 2 MUST deduplicate recordId transactionally. */
@Service
public class IntakeDelivery {
    private final IntakeOutboxMapper outbox;
    private final Integrations config;
    private final HealthInbox health;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    @org.springframework.beans.factory.annotation.Autowired
    public IntakeDelivery(IntakeOutboxMapper outbox,Integrations config,HealthInbox health) {
        this.outbox=outbox;this.config=config;this.health=health;
    }
    // Standalone HTTP delivery adapter used by contract tests.
    public IntakeDelivery(IntakeOutboxMapper outbox,Integrations config) {this(outbox,config,null);}
    public record DeliveryStatus(String state,int attempts,String lastError) {}
    @Scheduled(fixedDelayString="${nutrimarket.dietary.delivery-delay-ms:30000}",initialDelayString="${nutrimarket.dietary.delivery-delay-ms:30000}")
    public synchronized void deliverPending() {
        var c=config.healthAgent();
        if(c.endpoint().isBlank() && health==null) return;
        var pending=outbox.findPending(c.maxAttempts());
        for(var row:pending) {
            outbox.incrementAttempts(row.recordId());
            try {
                if(c.endpoint().isBlank()) {
                    health.accept(row.recordId());outbox.markDelivered(row.recordId());continue;
                }
                var request=HttpRequest.newBuilder(URI.create(c.endpoint())).timeout(c.timeout())
                        .header("Content-Type","application/json").header("Idempotency-Key",row.recordId());
                if(!c.apiKey().isBlank()) request.header("Authorization","Bearer "+c.apiKey());
                var response=http.send(request.POST(HttpRequest.BodyPublishers.ofString(row.payload())).build(),HttpResponse.BodyHandlers.discarding());
                if(response.statusCode()>=200 && response.statusCode()<300)
                    outbox.markDelivered(row.recordId());
                else failure(row.recordId(),"HEALTH_HTTP_"+response.statusCode());
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); failure(row.recordId(),"HEALTH_INTERRUPTED"); return;
            } catch(Exception e) { failure(row.recordId(),"HEALTH_UNAVAILABLE"); }
        }
    }
    private void failure(String id,String code) {
        outbox.recordFailure(id,code);
    }
    public DeliveryStatus status(UUID id) {
        var row=outbox.find(id.toString());
        if(row==null) throw AgentException.conflict("This record has not been added to the delivery queue.");
        String state=row.delivered()?"DELIVERED":config.healthAgent().endpoint().isBlank() && health==null?"WAITING_FOR_AGENT_2":
                row.attempts()>=config.healthAgent().maxAttempts()?"DELIVERY_FAILED":"QUEUED";
        return new DeliveryStatus(state,row.attempts(),row.lastError());
    }
}
