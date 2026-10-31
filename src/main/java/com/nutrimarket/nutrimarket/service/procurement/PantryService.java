package com.nutrimarket.nutrimarket.service.procurement;

import com.nutrimarket.nutrimarket.dao.health.HealthMapper;
import com.nutrimarket.nutrimarket.dao.procurement.ProcurementMapper;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.model.procurement.ProcurementModels.*;
import com.nutrimarket.nutrimarket.service.planning.IngredientCatalog;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The member's pantry, rebuilt from order history on every read: accepted lines add their shelf-stable surplus,
 * pantry use recorded on each basket line is taken out first-in first-out, and the member's corrections apply in time order.
 * Cancelled or rejected orders simply drop out, so nothing needs reversing.
 */
@Service
public class PantryService {
    private static final BigDecimal EMPTY=new BigDecimal("0.5");
    private final ProcurementMapper mapper;private final HealthMapper members;private final IngredientCatalog catalog;
    private final ObjectMapper json;private final TransactionTemplate tx;private final Clock clock;
    public PantryService(ProcurementMapper mapper,HealthMapper members,IngredientCatalog catalog,ObjectMapper json,TransactionTemplate tx,Clock clock) {
        this.mapper=mapper;this.members=members;this.catalog=catalog;this.json=json;this.tx=tx;this.clock=clock;
    }
    private static final class Item {
        final String key,ingredientId,productId,sku,name,store,orderId;final BigDecimal yieldFactor;final Instant addedAt;BigDecimal remaining;boolean adjusted;
        Item(String key,Line l,BigDecimal yieldFactor,Instant addedAt,String orderId){var f=l.product().facts();this.key=key;ingredientId=f.ingredientId();productId=l.product().productId();sku=f.sku();name=f.name();store=f.store();
            this.yieldFactor=yieldFactor;this.addedAt=addedAt;this.orderId=orderId;remaining=l.surplusGrams().setScale(1,RoundingMode.HALF_UP);}
        BigDecimal edible(){return remaining.multiply(yieldFactor);}
    }
    /** Edible (ready-to-eat) grams per ingredient, for the basket engine. */
    public Map<String,BigDecimal> available(String member) {
        Map<String,BigDecimal> result=new HashMap<>();stock(member).forEach(i->result.merge(i.ingredientId,i.edible(),BigDecimal::add));return result;
    }
    public PantryView view(String member) {
        var since=clock.instant().minus(Duration.ofDays(7));List<Purchase> purchases=new ArrayList<>();
        for(var o:orders(member))if(!o.createdAt.isBefore(since))for(var l:happened(o)){
            boolean bought=l.packages().signum()>0;var f=l.product().facts();
            purchases.add(new Purchase(o.orderId,o.createdAt,f.sku(),f.name(),f.store(),l.packages(),l.purchasedGrams(),l.requiredSoldGrams(),bought?l.surplusGrams():BigDecimal.ZERO,
                bought&&l.surplusGrams().signum()>0&&catalog.keepsInPantry(f.ingredientId(),f.preparation()),l.fromPantry()));
        }
        var stock=stock(member).stream().map(i->new PantryItem(i.key,i.ingredientId,i.productId,i.sku,i.name,i.store,i.remaining,i.yieldFactor,i.edible().setScale(0,RoundingMode.HALF_UP),i.addedAt,i.orderId,i.adjusted)).toList();
        return new PantryView(stock,purchases,since);
    }
    /** Sets what is left of one pantry item; zero removes it. */
    public PantryView correct(String member,PantryAdjustment input) {
        tx.executeWithoutResult(s->{
            if(members.lockMember(member)==null)throw missing();
            if(stock(member).stream().noneMatch(i->i.key.equals(input.key())))throw missing();
            var raw=mapper.pantry(member);var doc=raw==null?new PantryDocument():json.readValue(raw,PantryDocument.class);
            doc.corrections.add(new PantryCorrection(input.key(),input.remainingGrams().setScale(1,RoundingMode.HALF_UP),clock.instant()));
            if(raw==null)mapper.insertPantry(member,json.writeValueAsString(doc));else mapper.savePantry(member,json.writeValueAsString(doc));
        });
        return view(member);
    }
    private List<Item> stock(String member) {
        var raw=mapper.pantry(member);var corrections=raw==null?List.<PantryCorrection>of():json.readValue(raw,PantryDocument.class).corrections;
        var orders=orders(member);List<Item> items=new ArrayList<>();int c=0;
        for(var o:orders) {
            for(;c<corrections.size()&&corrections.get(c).at().isBefore(o.createdAt);c++)apply(items,corrections.get(c));
            var lines=happened(o);
            for(var l:lines)take(items,l.product().facts().ingredientId(),l.fromPantry());
            for(var l:lines){var f=l.product().facts();
                if(l.packages().signum()>0&&l.surplusGrams().signum()>0&&catalog.keepsInPantry(f.ingredientId(),f.preparation()))
                    items.add(new Item(o.orderId+":"+l.product().productId(),l,"RAW".equals(f.preparation())?f.cookedYieldFactor():BigDecimal.ONE,o.createdAt,o.orderId));}
        }
        for(;c<corrections.size();c++)apply(items,corrections.get(c));
        return items;
    }
    private static void apply(List<Item> items,PantryCorrection c) {
        items.stream().filter(i->i.key.equals(c.key())).findFirst().ifPresent(i->{i.remaining=c.remainingGrams();i.adjusted=true;});
        items.removeIf(i->i.remaining.compareTo(EMPTY)<0);
    }
    // Oldest stock is used first; grams are edible, converted back to weight as sold per item.
    private static void take(List<Item> items,String ingredient,BigDecimal edible) {
        var left=edible;
        for(var i:items){if(left.signum()<=0)break;if(!i.ingredientId.equals(ingredient))continue;
            var used=left.min(i.edible());i.remaining=i.remaining.subtract(used.divide(i.yieldFactor,1,RoundingMode.HALF_UP)).max(BigDecimal.ZERO);left=left.subtract(used);}
        items.removeIf(i->i.remaining.compareTo(EMPTY)<0);
    }
    private List<Order> orders(String member){return mapper.memberOrders(member).stream().map(r->json.readValue(r.document(),Order.class)).toList();}
    /** Lines the member actually has: accepted store lines, plus lines met entirely from the pantry (never sent to a store). */
    static List<Line> happened(Order o) {
        if("LOCAL_CONFIRMED".equals(o.status))return o.snapshot.evaluation.lines();
        if(!Set.of(ProcurementService.CONFIRMED,ProcurementService.PARTIAL).contains(o.status))return List.of();
        var ids=o.storeOrders.stream().filter(x->x.status()==StoreStatus.ACCEPTED||x.status()==StoreStatus.PARTIAL).flatMap(x->x.lines().stream()).filter(StoreLine::accepted).map(StoreLine::productId).collect(Collectors.toSet());
        return o.snapshot.evaluation.lines().stream().filter(l->l.packages().signum()==0||ids.contains(l.product().productId())).toList();
    }
    private static AgentException missing(){return new AgentException(HttpStatus.NOT_FOUND,"NOT_FOUND","Pantry item not found.");}
}
