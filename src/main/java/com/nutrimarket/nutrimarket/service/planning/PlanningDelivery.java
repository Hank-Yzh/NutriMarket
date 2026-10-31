package com.nutrimarket.nutrimarket.service.planning;

import com.nutrimarket.nutrimarket.config.PlanningPolicy;
import com.nutrimarket.nutrimarket.dao.planning.PlanningMapper;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.State;
import java.net.URI;
import java.net.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** At-least-once delivery. Receivers must deduplicate the versioned idempotency key. */
@Service
@ConditionalOnProperty(name="nutrimarket.planning.delivery-enabled",havingValue="true",matchIfMissing=true)
public class PlanningDelivery {
    private final PlanningMapper mapper;
    private final PlanningService service;
    private final PlanningPolicy policy;
    private final HttpClient http;
    private final com.nutrimarket.nutrimarket.service.market.MarketService market;
    public PlanningDelivery(PlanningMapper mapper,PlanningService service,PlanningPolicy policy) {this(mapper,service,policy,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public PlanningDelivery(PlanningMapper mapper,PlanningService service,PlanningPolicy policy,com.nutrimarket.nutrimarket.service.market.MarketService market) {
        this.market=market;
        this.mapper=mapper;this.service=service;this.policy=policy;
        http=HttpClient.newBuilder().connectTimeout(policy.marketTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Scheduled(fixedDelayString="${nutrimarket.planning.delivery-delay-ms:30000}",initialDelayString="${nutrimarket.planning.delivery-delay-ms:30000}")
    public synchronized void deliverPending() {
        if(policy.marketEndpoint().isBlank()&&market==null)return;
        for(var row:mapper.pending(policy.deliveryAttempts())) {
            try {
                var stored=mapper.find(row.planId());
                if(stored==null)continue;
                var current=service.get(stored.memberId(),row.planId());
                if(current.state!=State.PUBLISHED || current.version!=row.planVersion()){mapper.cancelVersion(row.planId(),row.planVersion());continue;}
                String payload=service.packageJsonFor(stored.memberId(),row.planId());
                if(policy.marketEndpoint().isBlank()){
                    market.start(stored.memberId(),new com.nutrimarket.nutrimarket.model.market.MarketModels.Start(java.util.UUID.fromString(row.planId()),row.planVersion()));
                    mapper.result(row.planId(),row.planVersion(),"DELIVERED",null);continue;
                }
                var request=HttpRequest.newBuilder(URI.create(policy.marketEndpoint())).timeout(policy.marketTimeout())
                    .header("Content-Type","application/json").header("Idempotency-Key",row.planId()+":"+row.planVersion());
                if(!policy.marketApiKey().isBlank())request.header("Authorization","Bearer "+policy.marketApiKey());
                int status=http.send(request.POST(HttpRequest.BodyPublishers.ofString(payload)).build(),HttpResponse.BodyHandlers.discarding()).statusCode();
                if(status>=200&&status<300)mapper.result(row.planId(),row.planVersion(),"DELIVERED",null);
                else failed(row,"HTTP_"+status);
            }catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            catch(Exception e){failed(row,e.getClass().getSimpleName());}
        }
    }
    private void failed(PlanningMapper.Outbox row,String error) {
        mapper.result(row.planId(),row.planVersion(),row.attempts()+1>=policy.deliveryAttempts()?"DELIVERY_FAILED":"QUEUED",error);
    }
}
