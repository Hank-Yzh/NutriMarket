package com.nutrimarket.nutrimarket.service.market;

import com.nutrimarket.nutrimarket.config.MarketPolicy;
import com.nutrimarket.nutrimarket.dao.auth.UserMapper;
import com.nutrimarket.nutrimarket.dao.health.HealthMapper;
import com.nutrimarket.nutrimarket.dao.market.MarketMapper;
import com.nutrimarket.nutrimarket.dao.planning.PlanningMapper;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.integration.market.MarketSemanticClient;
import com.nutrimarket.nutrimarket.model.market.MarketModels.*;
import com.nutrimarket.nutrimarket.model.market.MarketModels.Event;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.*;
import com.nutrimarket.nutrimarket.service.planning.PlanningService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.nutrimarket.nutrimarket.model.market.MarketModels.State;

@Service
public class MarketService {
    private final MarketMapper mapper;private final PlanningMapper plans;private final HealthMapper health;private final UserMapper users;
    private final PlanningService planning;private final MarketEngine engine;private final CatalogService catalog;private final MarketSemanticClient semantic;
    private final MarketPolicy policy;private final ObjectMapper json;private final TransactionTemplate tx;private final Clock clock;
    public MarketService(MarketMapper mapper,PlanningMapper plans,HealthMapper health,UserMapper users,PlanningService planning,MarketEngine engine,CatalogService catalog,MarketSemanticClient semantic,MarketPolicy policy,ObjectMapper json,TransactionTemplate tx,Clock clock){this.mapper=mapper;this.plans=plans;this.health=health;this.users=users;this.planning=planning;this.engine=engine;this.catalog=catalog;this.semantic=semantic;this.policy=policy;this.json=json;this.tx=tx;this.clock=clock;}
    public MarketResult start(String member,Start input){
        String token=UUID.randomUUID().toString();
        var r=tx.execute(s->{lock(member);var source=source(member,input.planId().toString());if(source.planVersion()!=input.planVersion())throw AgentException.conflict("The published plan version changed. Reload the plan.");engine.validate(source);
            var published=planning.get(member,input.planId().toString());
            if(!member.equals(source.memberId())||!published.planId.equals(source.planId())||!published.assessmentId.equals(source.assessmentId())||published.profileVersion!=source.profileVersion()||published.assessmentRevision!=source.assessmentRevision())throw invalidPackage();
            var existing=mapper.forPlan(source.planId(),source.planVersion());if(existing!=null)return decode(existing);
            var result=new MarketResult();result.resultId=UUID.randomUUID().toString();result.memberId=member;result.planId=source.planId();result.planVersion=source.planVersion();result.assessmentId=source.assessmentId();result.profileVersion=source.profileVersion();result.planning=source;result.policyVersion=policy.policyVersion();result.createdAt=clock.instant();result.updatedAt=result.createdAt;result.generationToken=token;result.state=State.MATCHING;event(result,"RECEIVED","Validated the immutable published PlanningPackage.");mapper.insert(result,json.writeValueAsString(result));
            if(plans.delivery(result.planId,result.planVersion).state().equals("QUEUED"))plans.result(result.planId,result.planVersion,"DELIVERED",null);return result;});
        return token.equals(r.generationToken)?run(r):get(member,r.resultId);
    }
    public MarketResult get(String member,String id){return tx.execute(s->{lock(member);var r=owned(member,id);boolean planCurrent=planCurrent(r);
        if(!planCurrent){r.planStale=true;stale(r,"The published plan, assessment, profile or planning week is no longer current.");}
        else if(!r.policyVersion.equals(policy.policyVersion()))stale(r,"The matching policy changed. Refresh before using these results.");
        else if(r.state==State.MATCHING&&r.updatedAt.plus(Duration.ofMinutes(20)).isBefore(clock.instant())){r.state=State.INTERRUPTED;r.generationToken=null;r.readyForProcurement=false;event(r,"INTERRUPTED","Matching lease expired. A bounded refresh can resume matching.");save(r);}
        else if(r.state!=State.MATCHING&&(r.requirements.stream().anyMatch(q->q.checkedAt().plus(policy.maxAge()).isBefore(clock.instant()))||r.requirements.stream().flatMap(x->x.candidates().stream()).anyMatch(m->engine.expired(m.product())||!catalog.current(m.product()))))stale(r,"Catalogue evidence has expired or changed. Refresh the affected menu lines.");
        var delivery=mapper.delivery(id,r.revision);r.deliveryState=delivery==null?"NOT_READY":delivery.state();
        r.priceComparison=engine.compare(r);return r;});}
    public ResultPage list(String member,int page,int size){return new ResultPage(mapper.list(member,size,(long)(page-1)*size).stream().map(r->get(member,r.resultId())).toList(),page,size);}
    public MarketResult refresh(String member,String id,Refresh input){return refresh(member,id,input,false);}
    /** Procurement's pre-order revalidation (MI-09). Agent 5 bounds it per basket, so it does not use the manual refresh budget. */
    public MarketResult refreshBeforeOrder(String member,String id,Refresh input){return refresh(member,id,input,true);}
    private MarketResult refresh(String member,String id,Refresh input,boolean preOrder){
        get(member,id);String token=UUID.randomUUID().toString();var r=tx.execute(s->{lock(member);var current=owned(member,id);if(!planCurrent(current))throw AgentException.conflict("This plan is no longer active. Match its new published version.");
            for(var old:current.refreshes)if(old.requestId().equals(input.requestId())){if(old.fromRevision()!=input.expectedRevision()||!old.menuLineIds().equals(input.menuLineIds()))throw AgentException.conflict("This refresh request ID was used with different content.");return current;}
            if(input.planId()!=null&&!input.planId().toString().equals(current.planId))throw AgentException.conflict("This refresh request is for a different plan.");
            if(input.requiredFreshAfter()!=null&&input.requiredFreshAfter().isAfter(clock.instant()))throw AgentException.conflict("The required freshness time cannot be in the future.");
            if(current.revision!=input.expectedRevision()||current.state==State.MATCHING)throw AgentException.conflict("Matching is in progress or the result revision changed. Reload first.");
            if(!preOrder&&current.refreshCount>=policy.maxRefreshes())throw AgentException.conflict("The market refresh limit has been reached. Operator review or a new published plan is required.");
            var lines=current.planning.weeklyMealPlan().stream().map(Meal::menuLineId).toList();if(!lines.containsAll(input.menuLineIds())||new HashSet<>(input.menuLineIds()).size()!=input.menuLineIds().size())throw AgentException.conflict("Invalid or duplicate affected menu lines.");
            current.refreshes.add(new RefreshAudit(input.requestId(),current.revision,List.copyOf(input.menuLineIds()),clock.instant(),input.basketId(),input.requiredFreshAfter()));current.requiredFreshAfter=input.requiredFreshAfter();current.revision++;if(preOrder)current.preOrderRefreshCount++;else current.refreshCount++;current.generationToken=token;current.state=State.MATCHING;current.readyForProcurement=false;current.affectedLines=List.copyOf(input.menuLineIds());current.policyVersion=policy.policyVersion();mapper.cancel(id);event(current,preOrder?"PRE_ORDER_REFRESH":"REFRESH_REQUESTED",preOrder?"Procurement requested current price, stock and constraint evidence before order confirmation.":"Rechecking selected lines; the original meals and substitution groups remain unchanged.");save(current);return current;});
        return token.equals(r.generationToken)?run(r):get(member,id);
    }
    private record Matched(List<ProductMatch> matches,boolean truncated) {}
    // One catalogue search and semantic scoring per food and description, shared by every meal line that needs it.
    private Matched match(MarketResult r,Requirement requirement,SubstitutionGroup group,Requirement scoring,Map<String,CatalogResult> products,Map<String,Map<String,BigDecimal>> scores){
        String key=scoring.ingredientId()+"|"+scoring.description();
        if(!products.containsKey(key)){var response=catalog.search(scoring.ingredientId(),scoring.description());
            products.put(key,new CatalogResult(response.products().stream().limit(policy.candidateLimit()).toList(),policy.source().name(),response.truncated()||response.products().size()>policy.candidateLimit()));}
        var found=products.get(key);
        if(!scores.containsKey(key)){Map<String,BigDecimal> values=new HashMap<>();if(policy.semanticEnabled()&&!found.products().isEmpty())values=semantic.match(scoring,found.products());else for(var p:found.products())values.put(p.productId(),BigDecimal.ONE);scores.put(key,values);}
        var values=scores.get(key);
        return new Matched(found.products().stream().map(p->engine.classify(r.planning,requirement,group,p,values.getOrDefault(p.productId(),BigDecimal.ZERO),policy.semanticEnabled()?"QWEN_SEMANTIC":"VERIFIED_CATALOGUE_MAPPING")).map(m->engine.requireFreshness(m,r.requiredFreshAfter)).toList(),found.truncated());
    }
    private MarketResult run(MarketResult r){
        String token=r.generationToken;Map<String,CatalogResult> products=new HashMap<>();Map<String,Map<String,BigDecimal>> scores=new HashMap<>();Map<String,String> failures=new HashMap<>();List<RequirementResult> result=new ArrayList<>();
        for(var originalRequirement:r.planning.ingredientRequirements()){
            var requirement=engine.purchaseRequirement(originalRequirement);
            var old=r.requirements.stream().filter(x->x.requirementId().equals(requirement.requirementId())).findFirst();
            if(!r.affectedLines.isEmpty()&&!r.affectedLines.contains(requirement.menuLineId())&&old.isPresent()){var q=old.get();
                if(q.outcome()==Outcome.STALE){var g=r.planning.substitutionGroups().stream().filter(x->x.substitutionGroupId().equals(requirement.substitutionGroupId())).findFirst().orElseThrow();
                    var retained=q.candidates().stream().map(m->catalog.current(m.product())?engine.classify(r.planning,requirement,g,m.product(),m.confidence(),m.matchingMethod()):m).toList();
                    var evaluated=engine.result(requirement,retained,q.messages(),q.truncated());q=new RequirementResult(q.requirementId(),q.menuLineId(),q.ingredientId(),q.requiredGrams(),evaluated.outcome(),q.messages(),retained,q.checkedAt(),q.truncated(),q.checkedSubstitutes());}
                result.add(q);continue;}
            var group=r.planning.substitutionGroups().stream().filter(g->g.substitutionGroupId().equals(requirement.substitutionGroupId())).findFirst().orElseThrow();
            String key=requirement.ingredientId()+"|"+requirement.description();
            try {
                if(failures.containsKey(key))throw AgentException.provider(failures.get(key),"The source or semantic matching service failed for this ingredient.");
                var planned=match(r,requirement,group,requirement,products,scores);
                List<ProductMatch> matches=new ArrayList<>(planned.matches());boolean truncated=planned.truncated();
                List<String> notes=new ArrayList<>();notes.add("Source: "+policy.source().name());
                if(matches.isEmpty())notes.add("No products returned by the configured catalogue. No price or stock was inferred.");
                // Every planner-approved substitute is checked in its own amount, even when the planned food is available, so Agent 5 receives all
                // eligible alternatives with their evidence. Agent 4 never chooses between them; a failed substitute search stays "not checked".
                boolean plannedAvailable=matches.stream().anyMatch(ProductMatch::eligible);List<String> checked=new ArrayList<>(),tried=new ArrayList<>();
                for(var sub:group.substituteList()){
                    var scoring=new Requirement(requirement.requirementId(),requirement.menuLineId(),sub.ingredientId(),engine.purchaseName(sub.ingredientId()),sub.quantity(),requirement.unit(),requirement.substitutionGroupId());
                    try{var found=match(r,requirement,group,scoring,products,scores);matches.addAll(found.matches());truncated|=found.truncated();checked.add(sub.ingredientId());}
                    catch(RuntimeException e){notes.add("Substitute "+engine.purchaseName(sub.ingredientId()).toLowerCase(Locale.ROOT)+" could not be checked: "+(e instanceof AgentException a?a.code():"CATALOGUE_INVALID_RESPONSE")+".");}
                    tried.add(engine.purchaseName(sub.ingredientId()).toLowerCase(Locale.ROOT)+" ("+sub.quantity().stripTrailingZeros().toPlainString()+" g)");
                }
                if(!tried.isEmpty())notes.add(plannedAvailable?"Planner-approved substitutes were also checked: "+String.join(", ",tried)+".":"No eligible "+requirement.description().toLowerCase(Locale.ROOT)+" product, so the meal plan's substitutes were searched: "+String.join(", ",tried)+".");
                if(truncated)notes.add("Candidate search was truncated; additional alternatives may exist.");
                result.add(engine.result(requirement,matches,notes,truncated,List.copyOf(checked)));
            }catch(RuntimeException e){String code=e instanceof AgentException a?a.code():"CATALOGUE_INVALID_RESPONSE";failures.put(key,code);result.add(new RequirementResult(requirement.requirementId(),requirement.menuLineId(),requirement.ingredientId(),requirement.quantity(),code.startsWith("MARKET_MODEL")?Outcome.MODEL_FAILURE:Outcome.SOURCE_FAILURE,List.of(code+": Matching could not be completed. Refresh after checking the configured source."),List.of(),clock.instant(),false));}
        }
        r.requirements=result;r.completedAt=clock.instant();r.readyForProcurement=result.size()==r.planning.ingredientRequirements().size()&&result.stream().allMatch(x->x.candidates().stream().anyMatch(ProductMatch::eligible));
        var eligible=result.stream().flatMap(x->x.candidates().stream()).filter(ProductMatch::eligible).toList();r.multiStoreComparable=eligible.stream().map(x->x.product().facts().store()).distinct().count()>1&&eligible.stream().map(x->x.product().facts().currency()).distinct().count()==1;
        r.state=r.readyForProcurement?State.COMPLETED:State.NEEDS_ATTENTION;r.generationToken=null;
        tx.executeWithoutResult(s->{lock(r.memberId);var latest=owned(r.memberId,r.resultId);if(latest.state!=State.MATCHING||!Objects.equals(token,latest.generationToken))return;
            if(!planCurrent(latest)){latest.planStale=true;stale(latest,"Plan changed during catalogue matching; late results were discarded.");return;}
            event(r,"MATCHING_COMPLETED",r.readyForProcurement?"Every requirement has at least one eligible candidate. No final SKU has been selected.":"Explicit blocked or missing outcomes require downstream recovery; autonomous procurement is not ready.");save(r);mapper.publish(r.resultId,r.revision,json.writeValueAsString(engine.pack(r)));});
        return get(r.memberId,r.resultId);
    }
    public ProcurementInputPackage packageFor(String member,String id){var checked=get(member,id);if(Set.of(State.MATCHING,State.STALE,State.INTERRUPTED).contains(checked.state))throw AgentException.conflict("Market information is incomplete, stale or still processing. Refresh it first.");
        return tx.execute(s->{lock(member);var r=owned(member,id);if(r.revision!=checked.revision||!planCurrent(r))throw AgentException.conflict("The published plan or market revision changed.");var outbox=mapper.delivery(id,r.revision);if(outbox==null||outbox.state().equals("CANCELLED"))throw AgentException.conflict("This procurement input is inactive.");return json.readValue(outbox.payload(),ProcurementInputPackage.class);});}
    private PlanningPackage source(String member,String id){
        try{return planning.packageFor(member,id);}catch(tools.jackson.core.JacksonException e){throw invalidPackage();}
    }
    private AgentException invalidPackage(){return new AgentException(HttpStatus.CONFLICT,"INVALID_PLANNING_PACKAGE","Published planning data is inconsistent or unreadable. Republish a valid plan.");}
    private boolean planCurrent(MarketResult r){var u=users.findById(r.memberId);if(u==null||!u.enabled())return false;var p=planning.get(r.memberId,r.planId);return p.state==com.nutrimarket.nutrimarket.model.planning.PlanningModels.State.PUBLISHED&&p.version==r.planVersion&&!p.stale;}
    private void stale(MarketResult r,String reason){if(r.state==State.STALE)return;r.state=State.STALE;r.readyForProcurement=false;r.multiStoreComparable=false;r.generationToken=null;
        r.requirements=r.requirements.stream().map(q->new RequirementResult(q.requirementId(),q.menuLineId(),q.ingredientId(),q.requiredGrams(),Outcome.STALE,List.of(reason),q.candidates().stream().map(m->new ProductMatch(m.product(),m.confidence(),m.matchingMethod(),m.hardConstraintStatus(),false,Outcome.STALE,List.of(reason),m.unitPricePer100g(),m.packagesToCover(),m.costToCover())).toList(),q.checkedAt(),q.truncated(),q.checkedSubstitutes())).toList();mapper.cancel(r.resultId);event(r,"INVALIDATED",reason);save(r);}
    private void event(MarketResult r,String action,String detail){r.updatedAt=clock.instant();r.audit.add(new Event(r.updatedAt,action,detail));}
    private void save(MarketResult r){mapper.update(r,json.writeValueAsString(r));}
    private MarketResult owned(String member,String id){var row=mapper.find(id);if(row==null||!row.memberId().equals(member))throw new AgentException(HttpStatus.NOT_FOUND,"NOT_FOUND","Market result not found.");return decode(row);}
    private MarketResult decode(MarketMapper.Row r){return json.readValue(r.document(),MarketResult.class);}
    private void lock(String member){if(health.lockMember(member)==null)throw new AgentException(HttpStatus.NOT_FOUND,"NOT_FOUND","Member not found.");}
}
