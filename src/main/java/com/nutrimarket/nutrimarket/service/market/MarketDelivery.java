package com.nutrimarket.nutrimarket.service.market;

import com.nutrimarket.nutrimarket.config.MarketPolicy;
import com.nutrimarket.nutrimarket.dao.market.MarketMapper;
import java.net.URI;
import java.net.http.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(name="nutrimarket.market.delivery-enabled",havingValue="true",matchIfMissing=true)
public class MarketDelivery {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(MarketDelivery.class);
    private final MarketMapper mapper;private final MarketService service;private final MarketPolicy policy;private final HttpClient http;private com.nutrimarket.nutrimarket.service.procurement.ProcurementService procurement;
    public MarketDelivery(MarketMapper mapper,MarketService service,MarketPolicy policy){this.mapper=mapper;this.service=service;this.policy=policy;this.http=HttpClient.newBuilder().connectTimeout(policy.procurementTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();}
    @org.springframework.beans.factory.annotation.Autowired
    public MarketDelivery(MarketMapper mapper,MarketService service,MarketPolicy policy,com.nutrimarket.nutrimarket.service.procurement.ProcurementService procurement){this(mapper,service,policy);this.procurement=procurement;}
    @Scheduled(fixedDelayString="${nutrimarket.market.delivery-delay-ms:30000}",initialDelayString="${nutrimarket.market.delivery-delay-ms:30000}")
    public synchronized void deliverPending(){if(policy.procurementEndpoint().isBlank()&&procurement==null)return;for(var row:mapper.pending(policy.deliveryAttempts())){
        try{var owner=mapper.find(row.resultId());var current=service.get(owner.memberId(),row.resultId());if(current.revision!=row.revision()||current.state==com.nutrimarket.nutrimarket.model.market.MarketModels.State.STALE){mapper.cancelVersion(row.resultId(),row.revision());continue;}service.packageFor(owner.memberId(),row.resultId());
            if(policy.procurementEndpoint().isBlank()){procurement.receive(owner.memberId(),row.resultId(),row.revision());continue;}
            var b=HttpRequest.newBuilder(URI.create(policy.procurementEndpoint())).timeout(policy.procurementTimeout()).header("Content-Type","application/json").header("Idempotency-Key",row.resultId()+":"+row.revision());if(!policy.procurementApiKey().isBlank())b.header("Authorization","Bearer "+policy.procurementApiKey());
            int status=http.send(b.POST(HttpRequest.BodyPublishers.ofString(row.payload())).build(),HttpResponse.BodyHandlers.discarding()).statusCode();mapper.delivered(row.resultId(),row.revision(),status>=200&&status<300?"DELIVERED":row.attempts()+1>=policy.deliveryAttempts()?"DELIVERY_FAILED":"QUEUED");
        }catch(InterruptedException e){Thread.currentThread().interrupt();return;}catch(Exception e){
            log.warn("Basket handoff failed: result={}, revision={}, attempt={}, code={}",row.resultId(),row.revision(),row.attempts()+1,e instanceof com.nutrimarket.nutrimarket.exception.AgentException a?a.code():e.getClass().getSimpleName());
            mapper.delivered(row.resultId(),row.revision(),row.attempts()+1>=policy.deliveryAttempts()?"DELIVERY_FAILED":"QUEUED");
        }
    }}
}
