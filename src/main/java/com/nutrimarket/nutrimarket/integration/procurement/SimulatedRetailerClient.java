package com.nutrimarket.nutrimarket.integration.procurement;

import com.nutrimarket.nutrimarket.config.ProcurementPolicy;
import com.nutrimarket.nutrimarket.model.procurement.ProcurementModels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Stand-in partner stores until a real supermarket order API is chosen. Nothing leaves the application, no payment is
 * taken and no stock is reserved. Every store accepts unless nutrimarket.procurement.retailer.simulated-outcomes lists it.
 */
@Component
public class SimulatedRetailerClient implements RetailerClient {
    private final ProcurementPolicy policy;
    public SimulatedRetailerClient(ProcurementPolicy policy){this.policy=policy;}
    @Override public RetailerResponse submit(RetailerRequest r){
        String reference="SIM-"+UUID.nameUUIDFromBytes((r.orderId()+"|"+r.store()).getBytes(StandardCharsets.UTF_8)).toString().substring(0,8).toUpperCase(Locale.ROOT);
        var skus=r.lines().stream().map(RetailerLine::sku).toList();
        var outcome=policy.retailer().simulatedOutcomes().stream().filter(o->o.store().equals(r.store())&&(!o.firstAttemptOnly()||r.attempt()==1)).findFirst();
        if(outcome.isEmpty())return new RetailerResponse(reference,StoreStatus.ACCEPTED,List.of(),null);
        var o=outcome.get();
        var rejected=o.outcome()==ProcurementPolicy.Outcome.REJECT?skus:skus.stream().filter(o.skus()::contains).toList();
        if(rejected.isEmpty())return new RetailerResponse(reference,StoreStatus.ACCEPTED,List.of(),null);
        return new RetailerResponse(reference,rejected.size()==skus.size()?StoreStatus.REJECTED:StoreStatus.PARTIAL,rejected,o.reason());
    }
    @Override public void cancel(String store,String reference){}
}
