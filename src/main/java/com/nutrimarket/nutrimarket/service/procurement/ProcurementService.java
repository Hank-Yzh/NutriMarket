package com.nutrimarket.nutrimarket.service.procurement;

import com.nutrimarket.nutrimarket.config.ProcurementPolicy;
import com.nutrimarket.nutrimarket.dao.auth.UserMapper;
import com.nutrimarket.nutrimarket.dao.health.HealthMapper;
import com.nutrimarket.nutrimarket.dao.market.MarketMapper;
import com.nutrimarket.nutrimarket.dao.procurement.ProcurementMapper;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.integration.procurement.RetailerClient;
import com.nutrimarket.nutrimarket.model.procurement.ProcurementModels.*;
import com.nutrimarket.nutrimarket.model.market.MarketModels.Outcome;
import com.nutrimarket.nutrimarket.model.market.MarketModels.Product;
import com.nutrimarket.nutrimarket.model.market.MarketModels.ProcurementInputPackage;
import com.nutrimarket.nutrimarket.model.market.MarketModels.Refresh;
import com.nutrimarket.nutrimarket.model.market.MarketModels.RequirementResult;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.FoodDislike;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.Plan;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.Meal;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.Replan;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.ReplanReason;
import com.nutrimarket.nutrimarket.service.health.HealthService;
import com.nutrimarket.nutrimarket.service.market.MarketService;
import com.nutrimarket.nutrimarket.service.planning.PlanningService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.nutrimarket.nutrimarket.model.procurement.ProcurementModels.State;

@Service
public class ProcurementService {
    // Order statuses. LOCAL_CONFIRMED / LOCAL_CANCELLED belong to orders recorded before store submission existed.
    static final String SUBMITTING="SUBMITTING",CONFIRMED="CONFIRMED",PARTIAL="PARTIALLY_FULFILLED",REJECTED="REJECTED",REOPTIMISING="REOPTIMISING",CANCELLED="CANCELLED";
    private static final Set<String> FAILED=Set.of(PARTIAL,REJECTED);
    private static final Map<String,String> NUTRIENTS=Map.of("1008","energy","1003","protein","1004","fat","1005","carbohydrate","1079","fibre","1093","sodium","1087","calcium","1089","iron");
    private final ProcurementMapper mapper;private final MarketMapper markets;private final HealthMapper members;
    private final UserMapper users;private final MarketService market;private final PlanningService planning;private final HealthService health;
    private final BasketEngine engine;private final PantryService pantry;private final RetailerClient retailer;private final ProcurementPolicy policy;private final ObjectMapper json;private final TransactionTemplate tx;private final Clock clock;
    public ProcurementService(ProcurementMapper mapper,MarketMapper markets,HealthMapper members,UserMapper users,MarketService market,PlanningService planning,HealthService health,BasketEngine engine,PantryService pantry,RetailerClient retailer,ProcurementPolicy policy,ObjectMapper json,TransactionTemplate tx,Clock clock){this.mapper=mapper;this.markets=markets;this.members=members;this.users=users;this.market=market;this.planning=planning;this.health=health;this.engine=engine;this.pantry=pantry;this.retailer=retailer;this.policy=policy;this.json=json;this.tx=tx;this.clock=clock;}
    public Basket start(String member,Start input){return receive(member,input.resultId().toString(),null);}
    public Basket receive(String member,String resultId,Integer revision){return tx.execute(s->{
        lock(member);var source=market.packageFor(member,resultId);
        if(revision!=null&&source.marketRevision()!=revision)throw AgentException.conflict("Market revision changed before delivery.");
        var existing=mapper.forResult(resultId);
        if(existing!=null){markets.delivered(resultId,source.marketRevision(),"DELIVERED");return owned(member,existing.id());}
        engine.validate(source);var b=new Basket();b.basketId=UUID.randomUUID().toString();b.memberId=member;b.resultId=resultId;b.planId=source.planId();b.assessmentId=source.assessmentId();b.planVersion=source.planVersion();b.profileVersion=source.profileVersion();b.marketRevision=source.marketRevision();b.source=source;b.policyVersion=policy.policyVersion();b.createdAt=clock.instant();
        String currency=source.marketIntelligenceResult().stream().flatMap(q->q.candidates().stream()).filter(m->m.eligible()).map(m->m.product().facts().currency()).findFirst().orElse("AUD");
        b.settings=learn(member,currency,source,b.learnedSignals);
        chooseDefault(b);event(b,"RECEIVED","Received verified market evidence."+(b.learnedSignals.isEmpty()?" Set your budget and preferences before checkout.":" Applied soft defaults from earlier baskets: "+String.join("; ",b.learnedSignals)+". They are editable and never become hard constraints."));
        mapper.insert(b,json.writeValueAsString(b));markets.delivered(resultId,b.marketRevision,"DELIVERED");return b;
    });}
    /**
     * PM-09: defaults come only from the member's own exclusions, disliked products, accepted baskets, fulfilment choices and saved stores.
     * Without an accepted basket the budget stays zero, so the member chooses a spending limit explicitly.
     */
    private Settings learn(String member,String currency,ProcurementInputPackage source,List<String> signals){
        Basket accepted=null,saved=null;Set<String> disliked=new LinkedHashSet<>();
        for(var row:mapper.baskets(member,20,0)){var x=decode(row);if(x.settings==null||!currency.equals(x.settings.currency()))continue;
            var ids=x.settings.itemEditList().stream().filter(e->e.choice()==ItemChoice.DISLIKE).map(e->e.productId().toString()).collect(Collectors.toSet());
            if(!ids.isEmpty())products(x.source).stream().filter(pr->ids.contains(pr.productId())).forEach(pr->disliked.add(pr.facts().sku()));
            if(accepted==null&&x.orderId!=null){var o=mapper.order(x.orderId);if(o!=null&&Set.of(CONFIRMED,PARTIAL,"LOCAL_CONFIRMED").contains(decodeOrder(o).status))accepted=x;}
            if(saved==null&&x.audit.stream().anyMatch(e->e.action().equals("PREFERENCES_UPDATED")))saved=x;
        }
        var from=accepted!=null?accepted:saved;var products=products(source);
        // A product the member disliked stays out of later baskets as a soft default, so a new plan's basket does not bring it back.
        var dislikedSkus=disliked.stream().filter(sku->products.stream().anyMatch(x->x.facts().sku().equalsIgnoreCase(sku))).toList();
        List<Exclusion> learnedDislikes=dislikedSkus.stream().map(sku->new Exclusion(ExclusionType.SKU,sku,true)).toList();
        String dislikedSignal=dislikedSkus.isEmpty()?null:"products you disliked "+String.join(", ",dislikedSkus);
        if(from==null){if(dislikedSignal!=null)signals.add(dislikedSignal);return new Settings(BigDecimal.ZERO,currency,Preference.BALANCED,Fulfilment.PICKUP,List.of(),List.of(),false,learnedDislikes);}
        var p=from.settings;
        var stores=p.preferredStores().stream().filter(st->products.stream().anyMatch(x->x.facts().store().equals(st))).toList();
        var exclusions=new ArrayList<>(p.exclusionList().stream().filter(e->products.stream().anyMatch(x->BasketEngine.excludes(e,x))).map(e->new Exclusion(e.type(),e.value(),true)).toList());
        for(var e:learnedDislikes)if(exclusions.stream().noneMatch(x->x.type()==e.type()&&x.value().equalsIgnoreCase(e.value())))exclusions.add(e);
        String origin=accepted!=null?"your accepted basket":"your saved preferences";
        signals.add("fulfilment "+p.fulfilment().name().toLowerCase(Locale.ROOT)+" and "+p.preference().name().toLowerCase(Locale.ROOT)+" preference from "+origin);
        if(!stores.isEmpty())signals.add("saved stores "+String.join(", ",stores));
        var kept=exclusions.stream().filter(e->!learnedDislikes.contains(e)).toList();
        if(!kept.isEmpty())signals.add("exclusions "+kept.stream().map(e->e.type().name().toLowerCase(Locale.ROOT)+" "+e.value()).collect(Collectors.joining(", ")));
        if(dislikedSignal!=null)signals.add(dislikedSignal);
        if(accepted!=null)signals.add("budget "+currency+" "+p.budget()+" from your accepted basket");
        return new Settings(accepted!=null?p.budget():BigDecimal.ZERO,currency,p.preference(),p.fulfilment(),stores,List.of(),false,exclusions);
    }
    public Basket get(String member,String id){return tx.execute(s->{lock(member);var b=owned(member,id);
        if(Set.of(State.ORDERED,State.TERMINATED,State.REVIEW_REQUIRED).contains(b.state))return b;
        var r=market.get(member,b.resultId);
        boolean invalid=r.planStale||r.state==com.nutrimarket.nutrimarket.model.market.MarketModels.State.STALE||!b.policyVersion.equals(policy.policyVersion());
        if(b.state==State.CHECKING){if(b.updatedAt.plusSeconds(1200).isBefore(clock.instant()))invalidate(b,"Checkout refresh was interrupted. Refresh again before confirmation.");return b;}
        if(invalid||r.revision!=b.marketRevision)invalidate(b,"The plan, profile, market revision or catalogue evidence changed. Refresh before checkout.");
        else if(b.state==State.CHECKOUT_READY&&b.checkoutAt.plus(Duration.ofMinutes(policy.checkoutMinutes())).isBefore(clock.instant()))invalidate(b,"Checkout confirmation expired. Refresh prices and availability again.");
        // Baskets created before UC-05 step 5 was enforced also open on the best complete draft, once, if the member has not edited them.
        else if(b.state==State.BLOCKED&&b.defaultChoice==null&&b.audit.stream().noneMatch(e->e.action().equals("PREFERENCES_UPDATED"))){
            var before=b.settings;chooseDefault(b);if(!b.settings.equals(before)){b.version++;b.checkoutAt=null;event(b,"DEFAULT_CHOSEN",b.defaultChoice);}else if(b.defaultChoice==null)b.defaultChoice="";save(b);}
        return b;
    });}
    public BasketPage list(String member,int page,int size){return new BasketPage(mapper.baskets(member,size,(long)(page-1)*size).stream().map(r->get(member,r.id())).toList(),page,size);}
    public Basket change(String member,String id,Change input){var current=get(member,id);
        // Evidence that expired or changed after the basket was built (such as a catalogue re-stamp) is re-checked first, as at checkout,
        // so the edit is applied to current prices and stock instead of being refused.
        var r=market.get(member,current.resultId);
        if(!r.planStale&&!FROZEN.contains(current.state)&&Set.of(com.nutrimarket.nutrimarket.model.market.MarketModels.State.STALE,com.nutrimarket.nutrimarket.model.market.MarketModels.State.INTERRUPTED).contains(r.state))
            market.refreshBeforeOrder(member,current.resultId,new Refresh(UUID.randomUUID(),r.revision,current.source.weeklyMealPlan().stream().map(Meal::menuLineId).toList(),UUID.fromString(current.planId),current.basketId,null));
        record Saved(Basket basket,List<String> foods,List<String> lines){}
        var saved=tx.execute(s->{lock(member);var b=owned(member,id);editable(b,expected(b,input.expectedVersion()));
        var before=b.settings.dislikedFoods();
        b.source=market.packageFor(member,b.resultId);b.marketRevision=b.source.marketRevision();b.settings=accept(b,input.settings());b.checkoutAt=null;b.policyVersion=policy.policyVersion();b.version++;b.materialChanges=new ArrayList<>();
        var foods=b.settings.dislikedFoods().stream().filter(f->!before.contains(f)).toList();
        var lines=b.source.weeklyMealPlan().stream().filter(m->m.ingredients().stream().anyMatch(i->foods.contains(i.ingredientId()))).map(Meal::menuLineId).toList();
        if(!lines.isEmpty()&&!Boolean.TRUE.equals(input.aiConsent()))throw new AgentException(HttpStatus.UNPROCESSABLE_CONTENT,"AI_CONSENT_REQUIRED","Changing the meals that use a food you dislike sends a replan request to Qwen. Agree to AI replanning, or choose Don't like this product instead.");
        evaluate(b);event(b,"PREFERENCES_UPDATED","Budget and preferences changed; the whole basket was re-optimised and checkout confirmation is required again.");save(b);return new Saved(b,foods,lines);
    });
        var changed=saved.basket();
        if(!saved.foods().isEmpty()){planning.dislike(member,saved.foods());return saved.lines().isEmpty()?changed:preferenceReplan(member,id,saved.foods(),saved.lines());}
        return changed.state==State.BLOCKED&&changed.settings.automaticReplanning()?autoReplan(member,id):changed;}
    /**
     * The member marked a food as disliked: it is remembered for later plans, and Agent 3 replaces only this plan's meals that use it.
     * A preference is not a supply failure, so a used-up replan budget leaves the meals unchanged instead of escalating to an expert.
     */
    private Basket preferenceReplan(String member,String id,List<String> foods,List<String> lines){
        String names=String.join(", ",planning.dislikes(member).stream().filter(f->foods.contains(f.ingredientId())).map(FoodDislike::name).toList());
        var b=tx.execute(s->{lock(member);var current=owned(member,id);var plan=planning.get(member,current.planId);
            String unchanged=null;
            if(plan.state!=com.nutrimarket.nutrimarket.model.planning.PlanningModels.State.PUBLISHED||plan.version!=current.planVersion)unchanged="This basket's plan is no longer the published version, so its meals were not changed.";
            else if(planning.replansLeft(member,current.planId)<=0)unchanged="This plan has used all of its replans, so its meals stay as they are.";
            if(unchanged!=null){event(current,"FOOD_DISLIKE_SAVED",names+" will be left out of your next meal plans. "+unchanged);save(current);return null;}
            var inPlan=foods.stream().filter(f->current.source.weeklyMealPlan().stream().filter(m->lines.contains(m.menuLineId())).anyMatch(m->m.ingredients().stream().anyMatch(i->f.equals(i.ingredientId())))).toList();
            var requestId=UUID.randomUUID();
            current.replanRequest=new Replan(requestId,current.planVersion,ReplanReason.PREFERENCE,"Member marked "+names+" as disliked in basket "+id+".",lines,inPlan,true,UUID.fromString(current.planId),plan.replans.size()+1);
            current.replanRequestId=requestId;current.replanFromVersion=current.version;current.replanStatus="REQUESTED";current.version++;current.state=State.STALE;
            event(current,"PREFERENCE_REPLAN_REQUESTED","You marked "+names+" as disliked. Agent 3 is replacing the "+lines.size()+" meal(s) that use it; publish the revised plan, which must pass Agent 4 again, to get a new basket. Later plans leave it out too.");save(current);return current;
        });
        if(b==null)return get(member,id);
        try{track(member,id,planning.replan(member,b.planId,b.replanRequest));}
        catch(AgentException e){tx.executeWithoutResult(s->{lock(member);var current=owned(member,id);current.replanStatus="FAILED";event(current,"PREFERENCE_REPLAN_FAILED",e.getMessage()+" "+names+" is still left out of your next meal plans.");save(current);});}
        return get(member,id);
    }
    // PM-07: brand or SKU exclusions can only narrow choices. Requests naming a hard constraint are rejected with the reason.
    private Settings accept(Basket basket,Settings s){
        var source=basket.source;
        var c=source.originalPlanningPackage().healthConstraintSet();Map<String,String> locked=new HashMap<>();
        c.excludedAllergens().forEach(a->locked.put(term(a),"allergy"));c.dietaryRestrictions().forEach(r->locked.put(term(r),"dietary restriction"));
        c.relevantConditions().forEach(x->locked.put(term(x),"health condition"));
        source.originalPlanningPackage().nutritionTargets().keySet().forEach(n->{if(NUTRIENTS.containsKey(n))locked.put(NUTRIENTS.get(n),"health red line (daily "+NUTRIENTS.get(n)+" target)");});
        var products=products(source);List<Exclusion> kept=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(var e:s.exclusionList()){
            String value=e.value().strip(),kind=e.type().name().toLowerCase(Locale.ROOT);
            if(locked.containsKey(term(value)))throw new AgentException(HttpStatus.UNPROCESSABLE_CONTENT,"EXCLUSION_REJECTED","\""+value+"\" is a "+locked.get(term(value))+" from your health profile. Allergies, dietary restrictions, health red lines and expert blocks are always enforced; basket exclusions cannot add, remove or waive them.");
            var exclusion=new Exclusion(e.type(),value,e.wasLearned());
            if(products.stream().noneMatch(p->BasketEngine.excludes(exclusion,p)))throw new AgentException(HttpStatus.UNPROCESSABLE_CONTENT,"EXCLUSION_REJECTED","No product in this basket's market evidence has "+kind+" \""+value+"\". Choose a listed brand or SKU.");
            if(seen.add(e.type()+"|"+term(value)))kept.add(exclusion);
        }
        List<ItemEdit> edits=new ArrayList<>();Set<UUID> edited=new HashSet<>();
        for(var edit:s.itemEditList()){
            if(!edited.add(edit.productId()))throw new AgentException(HttpStatus.UNPROCESSABLE_CONTENT,"ITEM_EDIT_REJECTED","Choose one action per product.");
            var line=basket.evaluation.lines().stream().filter(l->l.product().productId().equals(edit.productId().toString())).findFirst();
            var prior=basket.settings.itemEditList().stream().filter(e->e.productId().equals(edit.productId())).findFirst();
            var ingredients=edit.choice()==ItemChoice.DISLIKE_FOOD
                    ?products(source).stream().filter(x->x.productId().equals(edit.productId().toString())).findFirst().map(x->List.of(x.facts().ingredientId()))
                        .orElseGet(()->prior.filter(e->e.choice()==ItemChoice.DISLIKE_FOOD).map(ItemEdit::ingredientIds).orElse(null))
                    :line.map(l->l.allocations().stream().map(a->a.plannedIngredientId()==null?l.product().facts().ingredientId():a.plannedIngredientId()).distinct().sorted().toList())
                    .orElseGet(()->prior.map(ItemEdit::ingredientIds).orElse(null));
            if(ingredients==null||ingredients.isEmpty())throw new AgentException(HttpStatus.UNPROCESSABLE_CONTENT,"ITEM_EDIT_REJECTED","Only products in this basket can be edited. Reload the basket first.");
            edits.add(new ItemEdit(edit.productId(),edit.choice(),List.copyOf(ingredients)));
        }
        return new Settings(s.budget(),s.currency(),s.preference(),s.fulfilment(),s.preferredStores(),s.excludedProductIds(),s.autoReplan(),kept,List.copyOf(edits));
    }
    /** Compare six choices against the same evidence, budget and edits; never save, order or call a model. */
    public BasketComparison compare(String member,String id,Change input){
        record Snapshot(Basket basket,Settings settings,Map<String,BigDecimal> pantry){}
        var snapshot=tx.execute(s->{lock(member);var b=get(member,id);editable(b,input.expectedVersion());
            b.source=market.packageFor(member,b.resultId);return new Snapshot(b,accept(b,input.settings()),pantry.available(member));});
        var b=snapshot.basket();var settings=snapshot.settings();List<BasketOption> options=new ArrayList<>();
        for(var mode:Fulfilment.values())for(var preference:Preference.values()){
            var variant=new Settings(settings.budget(),settings.currency(),preference,mode,settings.preferredStores(),settings.excludedProductIds(),settings.autoReplan(),settings.exclusionList(),settings.itemEditList());
            options.add(option(preference,mode,engine.evaluate(b.source,variant,snapshot.pantry())));
        }
        return new BasketComparison(b.version,b.source.marketRevision(),settings.currency(),BigDecimal.valueOf(policy.balancedMargin()),List.copyOf(options));
    }
    private BasketOption option(Preference preference,Fulfilment mode,Evaluation e){
        var purchased=e.lines().stream().filter(l->l.packages().signum()>0).toList();List<StoreQuote> stores=new ArrayList<>();
        for(var name:purchased.stream().map(l->l.product().facts().store()).distinct().sorted().toList()){
            var products=purchased.stream().filter(l->l.product().facts().store().equals(name)).map(Line::subtotal).reduce(BigDecimal.ZERO,BigDecimal::add);
            var fee=e.fees().stream().filter(f->f.store().equals(name)).findFirst().orElseThrow();
            stores.add(new StoreQuote(name,products,fee.amount(),fee.quoted(),fee.quoted()?products.add(fee.amount()):null));
        }
        boolean quoted=stores.stream().allMatch(StoreQuote::feeQuoted);
        var fees=quoted?stores.stream().map(StoreQuote::fee).reduce(BigDecimal.ZERO,BigDecimal::add):null;
        var products=purchased.stream().map(l->{var f=l.product().facts();return new OptionProduct(l.product().productId(),f.name(),f.sku(),f.store(),f.qualityTier(),l.packages(),f.packageGrams(),f.price(),l.subtotal());}).toList();
        var issues=e.issues().isEmpty()&&e.lines().isEmpty()?List.of("No products to buy after your removals."):e.issues();
        return new BasketOption(preference,mode,issues.isEmpty(),e.productTotal(),fees,quoted?e.total():null,List.copyOf(stores),products,issues,e.searchLimited());
    }
    private static String term(String value){String t=value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");return t.endsWith("s")&&t.length()>3?t.substring(0,t.length()-1):t;}
    private static List<Product> products(ProcurementInputPackage source){Map<String,Product> all=new LinkedHashMap<>();source.marketIntelligenceResult().forEach(q->q.candidates().forEach(m->all.putIfAbsent(m.product().productId(),m.product())));return List.copyOf(all.values());}
    /** Refreshes Agent 4 evidence and rebuilds the basket; confirmation is a separate step. */
    public Basket checkout(String member,String id,Checkout input){return refresh(member,id,input,false).basket();}
    /** UC-06 steps 7-8: refresh and revalidate, then order directly unless something material changed (PM-08). */
    public SubmitResult submit(String member,String id,Checkout input){return refresh(member,id,input,true);}
    private SubmitResult refresh(String member,String id,Checkout input,boolean submit){
        record Attempt(Basket basket,boolean claimed) {}
        var attempt=tx.execute(s->{lock(member);var b=owned(member,id);
            if(input.requestId().equals(b.checkoutRequestId)){if(input.expectedVersion()!=b.checkoutFromVersion)throw AgentException.conflict("Checkout request ID was used with a different version.");return new Attempt(b,false);}
            editable(b,input.expectedVersion());
            if(submit&&b.settings.fulfilment()==Fulfilment.DELIVERY)requireAddress(member);
            if(submit&&b.evaluation.lines().isEmpty()&&b.evaluation.issues().isEmpty())throw AgentException.conflict("There are no products to order after your removals.");
            if(submit&&(b.evaluation.lines().isEmpty()||!b.evaluation.issues().isEmpty()))throw AgentException.conflict("Resolve the basket issues before submitting an order.");
            if(b.checkoutCount>=policy.maxCheckouts())throw AgentException.conflict("This basket reached its checkout refresh limit. Request expert review or start from a new market result.");
            var r=market.get(member,b.resultId);if(r.planStale)throw AgentException.conflict("The plan is stale. Publish and match its new version first.");
            b.checkoutCount++;b.checkoutRequestId=input.requestId();b.checkoutFromVersion=input.expectedVersion();b.checkoutAt=null;b.version++;b.state=State.CHECKING;
            event(b,submit?"SUBMIT_REQUESTED":"CHECKOUT_REFRESH","Requested current price, stock and constraint evidence from Agent 4"+(submit?" before submitting the order.":"."));save(b);return new Attempt(b,true);
        });
        var work=attempt.basket();
        if(!attempt.claimed()){var b=get(member,id);return new SubmitResult(b,b.state==State.ORDERED&&b.orderId!=null?order(member,b.orderId):null);}
        try{
            var r=market.get(member,work.resultId);
            market.refreshBeforeOrder(member,work.resultId,new Refresh(input.requestId(),r.revision,work.source.weeklyMealPlan().stream().map(Meal::menuLineId).toList(),
                UUID.fromString(work.planId),work.basketId,clock.instant().minus(policy.checkoutEvidenceAge())));
        }catch(RuntimeException e){return new SubmitResult(tx.execute(s->{lock(member);var b=owned(member,id);if(b.state==State.CHECKING&&input.requestId().equals(b.checkoutRequestId)){
            b.state=State.BLOCKED;b.checkoutAt=null;String detail=e instanceof AgentException?e.getMessage():"Market refresh failed. Try again or request review.";
            var v=b.evaluation;b.evaluation=new Evaluation(v.lines(),v.total(),List.of(detail),List.of(),v.searchLimited(),v.explanation(),v.productTotal(),v.fees(),v.promotionDecision(),v.qualityDecision());event(b,"CHECKOUT_BLOCKED",detail);save(b);}return b;}),null);}
        var refreshed=tx.execute(s->{lock(member);var b=owned(member,id);if(b.state!=State.CHECKING||!input.requestId().equals(b.checkoutRequestId))return b;
            var reviewed=b.evaluation;
            b.source=market.packageFor(member,b.resultId);b.marketRevision=b.source.marketRevision();b.policyVersion=policy.policyVersion();evaluate(b);
            b.materialChanges=new ArrayList<>(engine.changes(reviewed,b.evaluation,b.source,Map.of()));
            if(b.state==State.DRAFT){b.state=State.CHECKOUT_READY;b.checkoutAt=clock.instant();}
            event(b,"CHECKOUT_REFRESHED",b.state==State.CHECKOUT_READY?(b.materialChanges.isEmpty()?"Prices, stock, products and constraints were revalidated with no material change.":b.materialChanges.size()+" material change(s) found; the member must re-confirm before any order is placed."):"The refreshed basket cannot be ordered: "+String.join(" ",b.evaluation.issues()));
            save(b);return b;});
        if(refreshed.state==State.BLOCKED&&refreshed.settings.automaticReplanning())return new SubmitResult(autoReplan(member,id),null);
        if(submit&&refreshed.state==State.CHECKOUT_READY&&refreshed.materialChanges.isEmpty()&&input.requestId().equals(refreshed.checkoutRequestId)){
            var order=place(member,id,input.requestId(),refreshed.version,false);return new SubmitResult(get(member,id),order);
        }
        return new SubmitResult(refreshed,null);
    }
    /** Screen 3: the member re-confirms the refreshed basket and every listed material change. */
    public Order confirm(String member,String id,Confirm input){
        if(!input.confirmOrder())throw AgentException.conflict("Explicit confirmation of the refreshed basket is required.");
        return place(member,id,input.requestId(),input.expectedVersion(),true);
    }
    /** UC-06 failure condition: the member declines the material changes, so no order is placed. */
    public Basket decline(String member,String id,Version input){return tx.execute(s->{lock(member);var b=owned(member,id);
        if(b.state!=State.CHECKOUT_READY||b.version!=input.expectedVersion())throw AgentException.conflict("There are no pending changes to decline. Reload the basket.");
        b.state=State.DRAFT;b.checkoutAt=null;b.version++;event(b,"CHANGES_DECLINED","Member declined "+b.materialChanges.size()+" material change(s); no order was placed.");save(b);return b;});}
    private Order place(String member,String id,UUID requestId,int expectedVersion,boolean reconfirmed){
        var placed=tx.execute(s->{lock(member);
            var duplicate=mapper.request(member,requestId.toString());
            if(duplicate!=null){var order=decodeOrder(duplicate);if(!order.basketId.equals(id)||order.basketVersion!=expectedVersion)throw AgentException.conflict("Order request ID was used with different content.");return order;}
            var b=owned(member,id);
            if(b.orderId!=null){var o=decodeOrder(mapper.order(b.orderId));if(o.requestIds.contains(requestId)){if(o.basketVersion!=expectedVersion)throw AgentException.conflict("Order request ID was used with different content.");return o;}}
            if(b.state!=State.CHECKOUT_READY||b.version!=expectedVersion||b.checkoutAt==null||b.checkoutAt.plus(Duration.ofMinutes(policy.checkoutMinutes())).isBefore(clock.instant()))throw AgentException.conflict("Refresh checkout and confirm the current basket first.");
            if(!reconfirmed&&!b.materialChanges.isEmpty())throw AgentException.conflict("Prices, stock or products changed. Review and re-confirm the changes first.");
            var source=market.packageFor(member,b.resultId);if(source.marketRevision()!=b.marketRevision||!source.equals(b.source)||!b.policyVersion.equals(policy.policyVersion()))throw AgentException.conflict("The source or policy changed. Refresh and confirm the revised basket.");
            var checked=engine.evaluate(source,b.settings,pantry.available(member));if(!checked.issues().isEmpty()||!checked.lines().equals(b.evaluation.lines())||checked.total().compareTo(b.evaluation.total())!=0)throw AgentException.conflict("Products or constraints changed. Refresh and review your basket again.");
            if(checked.lines().isEmpty())throw AgentException.conflict("There are no products to order after your removals.");
            Order o;var existing=b.orderId==null?null:decodeOrder(mapper.order(b.orderId));
            if(existing!=null){if(!REOPTIMISING.equals(existing.status))throw AgentException.conflict("This basket already has an order.");o=existing;o.submission++;}
            else{o=new Order();o.orderId=UUID.randomUUID().toString();o.memberId=member;o.basketId=id;o.requestId=requestId;o.createdAt=clock.instant();o.mode="SIMULATED_RETAILER";}
            o.requestIds.add(requestId);o.basketVersion=b.version;o.currency=b.settings.currency();o.total=checked.total();o.status=SUBMITTING;o.cancelledAt=null;o.updatedAt=clock.instant();
            o.planId=b.planId;o.planVersion=b.planVersion;o.resultId=b.resultId;o.marketRevision=b.marketRevision;
            o.selectedSkus=checked.lines().stream().map(l->l.product().facts().sku()).toList();o.preferenceSignals=b.settings;o.promotionDecision=checked.promotionDecision();o.qualityDecision=checked.qualityDecision();
            // Checked inside the order transaction, so a delivery order always carries the address it was placed with.
            o.deliveryAddress=b.settings.fulfilment()==Fulfilment.DELIVERY?requireAddress(member):null;o.storeOrders=storeOrders(b,checked,o.deliveryAddress);
            b.state=State.ORDERED;b.orderId=o.orderId;b.checkoutAt=null;
            event(b,"ORDER_SUBMITTED",(reconfirmed?"Member re-confirmed the refreshed basket"+(b.materialChanges.isEmpty()?"":" and "+b.materialChanges.size()+" material change(s)"):"No material change after revalidation, so the reviewed basket was submitted directly")+". Submitted to "+o.storeOrders.size()+" simulated store(s).");
            save(b);o.snapshot=b;orderEvent(o,"SUBMITTED","Submission "+o.submission+" to "+o.storeOrders.stream().map(StoreOrder::store).collect(Collectors.joining(", "))+".");
            if(existing==null)mapper.insertOrder(o,o.requestId.toString(),json.writeValueAsString(o));else mapper.saveOrder(o.orderId,json.writeValueAsString(o));
            return o;
        });
        return SUBMITTING.equals(placed.status)?send(member,placed.orderId,"STORE_RESPONSES"):placed;
    }
    private List<StoreOrder> storeOrders(Basket b,Evaluation e,DeliveryAddress address){
        // Lines met entirely from the pantry are not sent to any store.
        Map<String,List<Line>> byStore=new TreeMap<>();e.lines().stream().filter(l->l.packages().signum()>0).forEach(l->byStore.computeIfAbsent(l.product().facts().store(),k->new ArrayList<>()).add(l));
        return byStore.entrySet().stream().map(x->{var fee=e.fees().stream().filter(f->f.store().equals(x.getKey())).findFirst().map(StoreFee::amount).orElse(null);
            return new StoreOrder(x.getKey(),b.settings.fulfilment(),StoreStatus.PENDING,0,null,x.getValue().stream().map(l->new StoreLine(l.product().productId(),l.product().facts().sku(),l.product().facts().name(),l.packages(),l.subtotal(),false,null)).toList(),fee,null,fulfilmentDetail(b,x.getKey(),address),clock.instant());}).toList();
    }
    // Simulated store slot: the day before the plan week starts, or tomorrow when that day has passed.
    private String fulfilmentDetail(Basket b,String store,DeliveryAddress address){
        var today=clock.instant().atZone(ZoneOffset.UTC).toLocalDate();var day=b.source.originalPlanningPackage().weekStart().minusDays(1);if(!day.isAfter(today))day=today.plusDays(1);
        String date=day.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy",Locale.ENGLISH));
        var at=policy.storeAddresses().get(store);String where=at==null?store:store+", "+at;
        return b.settings.fulfilment()==Fulfilment.PICKUP?"Pickup at "+where+", "+date+", 10:00-18:00 (simulated slot)":"Home delivery from "+where+" to "+address.text()+", "+date+", 08:00-12:00 (simulated slot)"+(address.instructions()==null||address.instructions().isBlank()?"":". Instructions: "+address.instructions().strip());
    }
    // Store calls run outside the member lock; responses are recorded only if the order is still in the same submission.
    private Order send(String member,String orderId,String action){
        var o=order(member,orderId);int submission=o.submission;List<StoreOrder> results=new ArrayList<>();
        for(var so:o.storeOrders){
            if(so.status()==StoreStatus.ACCEPTED||so.status()==StoreStatus.CANCELLED||so.attempts()>=policy.retailer().maxAttempts()){results.add(so);continue;}
            var pending=so.lines().stream().filter(l->!l.accepted()).toList();int attempt=so.attempts()+1;RetailerResponse r;
            try{r=retailer.submit(new RetailerRequest(o.orderId,so.store(),attempt,so.fulfilment(),pending.stream().map(l->new RetailerLine(l.productId(),l.sku(),l.name(),l.packages())).toList()));}
            catch(RuntimeException e){r=new RetailerResponse(so.retailerReference(),StoreStatus.REJECTED,pending.stream().map(StoreLine::sku).toList(),"The store could not be reached.");}
            var response=r;
            var lines=so.lines().stream().map(l->l.accepted()?l:new StoreLine(l.productId(),l.sku(),l.name(),l.packages(),l.subtotal(),!response.rejectedSkus().contains(l.sku()),response.rejectedSkus().contains(l.sku())?response.reason():null)).toList();
            boolean all=lines.stream().allMatch(StoreLine::accepted),none=lines.stream().noneMatch(StoreLine::accepted);
            results.add(new StoreOrder(so.store(),so.fulfilment(),all?StoreStatus.ACCEPTED:none?StoreStatus.REJECTED:StoreStatus.PARTIAL,attempt,r.reference(),lines,so.fee(),all?null:r.reason(),so.fulfilmentDetail(),clock.instant()));
        }
        return tx.execute(s->{lock(member);var current=order(member,orderId);
            if(!SUBMITTING.equals(current.status)||current.submission!=submission)return current;
            current.storeOrders=results;current.updatedAt=clock.instant();
            current.status=results.stream().allMatch(x->x.status()==StoreStatus.ACCEPTED)?CONFIRMED:results.stream().noneMatch(x->x.status()==StoreStatus.ACCEPTED||x.status()==StoreStatus.PARTIAL)?REJECTED:PARTIAL;
            String summary=results.stream().map(x->x.store()+" "+x.status()+(x.reason()==null?"":" ("+x.reason()+")")).collect(Collectors.joining("; "));
            orderEvent(current,action,summary+".");mapper.saveOrder(orderId,json.writeValueAsString(current));
            var b=owned(member,current.basketId);event(b,"ORDER_"+current.status,summary+(FAILED.contains(current.status)?". Choose recovery, re-optimisation or cancellation; the order cannot be submitted twice.":"."));save(b);
            return current;});
    }
    /** UC-06 A4 recovery: resubmit only the rejected lines to the same stores, within retailer.max-attempts. */
    public Order recover(String member,String id,OrderAction input){
        var claimed=tx.execute(s->{lock(member);var o=order(member,id);
            if(input.requestId().equals(o.actionRequestId))return null;
            boolean stuck=SUBMITTING.equals(o.status)&&o.updatedAt.plusSeconds(120).isBefore(clock.instant());
            if(!FAILED.contains(o.status)&&!stuck)throw AgentException.conflict("Only rejected or partially fulfilled orders can be recovered.");
            if(o.storeOrders.stream().filter(x->x.status()!=StoreStatus.ACCEPTED&&x.status()!=StoreStatus.CANCELLED).allMatch(x->x.attempts()>=policy.retailer().maxAttempts()))throw AgentException.conflict("Recovery attempts are exhausted. Re-optimise the basket or cancel the order.");
            o.actionRequestId=input.requestId();o.status=SUBMITTING;o.updatedAt=clock.instant();orderEvent(o,"RECOVERY_REQUESTED","Resubmitting rejected lines to the same stores.");mapper.saveOrder(id,json.writeValueAsString(o));return o;});
        return claimed==null?order(member,id):send(member,id,"RECOVERY_ATTEMPTED");
    }
    /** UC-06 A4 re-optimisation: cancel the accepted store orders and rebuild the whole basket without the rejected SKUs. */
    public Basket reoptimise(String member,String id,OrderAction input){return tx.execute(s->{lock(member);var o=order(member,id);
        if(input.requestId().equals(o.actionRequestId)&&REOPTIMISING.equals(o.status))return owned(member,o.basketId);
        if(!FAILED.contains(o.status))throw AgentException.conflict("Only rejected or partially fulfilled orders can be re-optimised.");
        Map<String,String> reasons=new HashMap<>();Set<String> rejected=new TreeSet<>();
        for(var so:o.storeOrders){if(so.retailerReference()!=null&&so.status()==StoreStatus.PARTIAL)retailer.cancel(so.store(),so.retailerReference());
            if(so.retailerReference()!=null&&so.status()==StoreStatus.ACCEPTED)retailer.cancel(so.store(),so.retailerReference());
            so.lines().stream().filter(l->!l.accepted()).forEach(l->{rejected.add(l.sku());reasons.put(l.productId(),"Replaced because "+so.store()+" rejected it: "+Objects.toString(l.reason(),"no reason given")+".");});}
        o.history.add(new Submission(o.submission,o.updatedAt,o.status,o.total,o.storeOrders.stream().map(x->x.status()==StoreStatus.REJECTED?x:new StoreOrder(x.store(),x.fulfilment(),StoreStatus.CANCELLED,x.attempts(),x.retailerReference(),x.lines(),x.fee(),"Cancelled for re-optimisation.",x.fulfilmentDetail(),clock.instant())).toList()));
        o.storeOrders=new ArrayList<>();o.status=REOPTIMISING;o.actionRequestId=input.requestId();o.updatedAt=clock.instant();
        orderEvent(o,"REOPTIMISATION_REQUESTED","Accepted store orders were cancelled. Rejected SKUs "+String.join(", ",rejected)+" are excluded and the whole basket is rebuilt for review.");mapper.saveOrder(id,json.writeValueAsString(o));
        var b=owned(member,o.basketId);var previous=b.evaluation;List<Exclusion> exclusions=new ArrayList<>(b.settings.exclusionList());
        for(String sku:rejected)if(exclusions.stream().noneMatch(e->e.type()==ExclusionType.SKU&&e.value().equalsIgnoreCase(sku)))exclusions.add(new Exclusion(ExclusionType.SKU,sku,false));
        var p=b.settings;b.settings=new Settings(p.budget(),p.currency(),p.preference(),p.fulfilment(),p.preferredStores(),p.excludedProductIds(),p.autoReplan(),exclusions,p.itemEditList());
        b.source=market.packageFor(member,b.resultId);b.marketRevision=b.source.marketRevision();b.policyVersion=policy.policyVersion();b.checkoutAt=null;b.version++;
        evaluate(b);b.materialChanges=new ArrayList<>(engine.changes(previous,b.evaluation,b.source,reasons));
        event(b,"REOPTIMISED_AFTER_REJECTION","Rebuilt the whole basket without "+String.join(", ",rejected)+". Review the replacements, then submit again.");save(b);return b;
    });}
    public Order order(String member,String id){var r=mapper.order(id);if(r==null||!r.memberId().equals(member))throw notFound();return decodeOrder(r);}
    public OrderPage orders(String member,int page,int size){return new OrderPage(mapper.orders(member,size,(long)(page-1)*size).stream().map(this::decodeOrder).toList(),page,size);}
    public Order cancel(String member,String id){return tx.execute(s->{lock(member);var o=order(member,id);
        if(o.status.equals("LOCAL_CONFIRMED")){o.status="LOCAL_CANCELLED";o.cancelledAt=clock.instant();mapper.saveOrder(id,json.writeValueAsString(o));return o;}
        if(Set.of(CANCELLED,"LOCAL_CANCELLED").contains(o.status))return o;
        if(SUBMITTING.equals(o.status)&&!o.updatedAt.plusSeconds(120).isBefore(clock.instant()))throw AgentException.conflict("The order is being submitted. Reload in a moment.");
        for(var so:o.storeOrders)if(so.retailerReference()!=null&&(so.status()==StoreStatus.ACCEPTED||so.status()==StoreStatus.PARTIAL))retailer.cancel(so.store(),so.retailerReference());
        o.storeOrders=o.storeOrders.stream().map(x->x.status()==StoreStatus.REJECTED?x:new StoreOrder(x.store(),x.fulfilment(),StoreStatus.CANCELLED,x.attempts(),x.retailerReference(),x.lines(),x.fee(),"Cancelled by the member.",x.fulfilmentDetail(),clock.instant())).toList();
        boolean reopened=REOPTIMISING.equals(o.status);o.status=CANCELLED;o.cancelledAt=clock.instant();o.updatedAt=o.cancelledAt;orderEvent(o,"CANCELLED","Member cancelled the order; accepted store orders were cancelled.");mapper.saveOrder(id,json.writeValueAsString(o));
        var b=owned(member,o.basketId);if(reopened){b.state=State.ORDERED;b.checkoutAt=null;b.version++;}event(b,"ORDER_CANCELLED","Member cancelled order "+o.orderId+"; no order remains for this basket.");save(b);
        return o;});}
    public Plan replan(String member,String id,ReplanInput input){
        var b=tx.execute(s->{lock(member);var current=owned(member,id);
            if(input.requestId().equals(current.replanRequestId)){if(input.expectedVersion()!=current.replanFromVersion||!input.aiConsent())throw AgentException.conflict("Replan request content changed.");return current;}
            editable(current,input.expectedVersion());if(!input.aiConsent())throw AgentException.conflict("AI planning consent is required.");
            var source=market.packageFor(member,current.resultId);
            if(!replannable(source))throw AgentException.conflict("Replanning requires complete current evidence that no permitted product can fulfil a meal. Refresh failed or truncated searches first; adjust budget or exclusions separately.");
            requestReplan(member,current,source,input.requestId(),input.expectedVersion(),"REPLAN_REQUESTED","Requested bounded replanning using market evidence. The revised plan must pass Agent 4 again.");return current;
        });
        try{var plan=planning.replan(member,b.planId,b.replanRequest);track(member,id,plan);return plan;}
        catch(AgentException e){if(!"REPLAN_LIMIT_REACHED".equals(e.code()))throw e;escalateReplan(member,id);throw AgentException.conflict("The replan limit for this plan has been reached, so this basket was sent to a Human Expert for review.");}
    }
    // PM-06: with the member's standing consent, Agent 5 issues the ReplanRequest itself once current evidence shows an unbuyable meal.
    private Basket autoReplan(String member,String id){
        var b=tx.execute(s->{lock(member);var current=owned(member,id);
            if(!current.settings.automaticReplanning()||current.state!=State.BLOCKED||current.evaluation.unfulfilledMenuLines().isEmpty())return null;
            var source=market.packageFor(member,current.resultId);if(!replannable(source))return null;
            requestReplan(member,current,source,UUID.randomUUID(),current.version,"AUTO_REPLAN_REQUESTED","Agent 5 issued a bounded ReplanRequest because current evidence shows no eligible product for a meal. Publish the revised plan; it must pass Agent 4 again.");return current;
        });
        if(b==null)return get(member,id);
        try{track(member,id,planning.replan(member,b.planId,b.replanRequest));}
        catch(AgentException e){if("REPLAN_LIMIT_REACHED".equals(e.code()))escalateReplan(member,id);else tx.executeWithoutResult(s->{lock(member);var current=owned(member,id);current.replanStatus="FAILED";event(current,"AUTO_REPLAN_FAILED",e.getMessage());save(current);});}
        return get(member,id);
    }
    private void track(String member,String id,Plan plan){tx.executeWithoutResult(s->{lock(member);var b=owned(member,id);b.replanStatus="REQUESTED";save(b);});}
    // UC-06 A1: once Agent 3 refuses further replans, the basket (not the plan) goes to Human Expert review.
    private void escalateReplan(String member,String id){tx.executeWithoutResult(s->{lock(member);var b=owned(member,id);if(b.state==State.REVIEW_REQUIRED)return;
        b.version++;b.state=State.REVIEW_REQUIRED;b.checkoutAt=null;b.replanStatus="ESCALATED";event(b,"REPLAN_LIMIT_REACHED","The configured replan limit for plan "+b.planId+" was reached, so this basket was escalated to Human Expert review. No order can be placed until an expert decides.");save(b);});}
    private List<RequirementResult> blocked(ProcurementInputPackage source){return source.marketIntelligenceResult().stream().filter(q->q.candidates().stream().noneMatch(m->m.eligible())).toList();}
    // Only complete, current evidence can prove a meal unbuyable; failed, stale or truncated searches cannot.
    private boolean replannable(ProcurementInputPackage source){var blocked=blocked(source);return !blocked.isEmpty()&&blocked.stream().noneMatch(q->q.truncated()||Set.of(Outcome.SOURCE_FAILURE,Outcome.MODEL_FAILURE,Outcome.STALE).contains(q.outcome()));}
    private void requestReplan(String member,Basket current,ProcurementInputPackage source,UUID requestId,int fromVersion,String action,String detail){
        var blocked=blocked(source);var lines=blocked.stream().map(q->q.menuLineId()).distinct().toList();var ingredients=blocked.stream().map(q->q.ingredientId()).distinct().toList();
        String evidence="Market result "+source.resultId()+" revision "+source.marketRevision()+": "+blocked.stream().map(q->q.requirementId()+"="+q.outcome()).reduce((a,c)->a+"; "+c).orElse("");
        if(evidence.length()>1900)evidence=evidence.substring(0,1900);
        // Reason code: AVAILABILITY when every blocked requirement is only out of stock, otherwise ELIGIBILITY.
        var reason=blocked.stream().allMatch(q->q.outcome()==Outcome.UNAVAILABLE)?ReplanReason.AVAILABILITY:ReplanReason.ELIGIBILITY;
        int attempt=planning.get(member,current.planId).replans.size()+1;
        current.replanRequest=new Replan(requestId,source.planVersion(),reason,evidence,lines,ingredients,true,UUID.fromString(current.planId),attempt);
        current.replanRequestId=requestId;current.replanFromVersion=fromVersion;current.replanStatus="REQUESTED";current.version++;current.state=State.STALE;event(current,action,detail+" Reason "+reason+", attempt "+attempt+".");save(current);
    }
    /** The member's saved delivery address, used only for home-delivery store orders. */
    public AddressView address(String member){var raw=mapper.address(member);return new AddressView(raw==null?null:json.readValue(raw,DeliveryAddress.class));}
    public AddressView saveAddress(String member,DeliveryAddress input){
        var clean=new DeliveryAddress(input.recipient().strip(),input.line1().strip(),blank(input.line2()),input.suburb().strip(),input.state(),input.postcode(),blank(input.instructions()));
        tx.executeWithoutResult(s->{lock(member);var doc=json.writeValueAsString(clean);if(mapper.address(member)==null)mapper.insertAddress(member,doc);else mapper.saveAddress(member,doc);});
        return new AddressView(clean);
    }
    private static String blank(String s){return s==null||s.isBlank()?null:s.strip();}
    private DeliveryAddress requireAddress(String member){
        var a=address(member).address();
        if(a==null)throw new AgentException(HttpStatus.CONFLICT,"DELIVERY_ADDRESS_REQUIRED","Add a delivery address before ordering home delivery, or choose pick-up.");
        return a;
    }
    public Basket escalate(String member,String id,Version v){return tx.execute(s->{lock(member);var b=owned(member,id);editable(b,v.expectedVersion());b.version++;b.state=State.REVIEW_REQUIRED;b.checkoutAt=null;event(b,"REVIEW_REQUESTED","Member requested expert review of unresolved procurement constraints.");save(b);return b;});}
    public BasketPage queue(String actor,int page,int size){health.requireExpert(actor);return new BasketPage(mapper.queue(size,(long)(page-1)*size).stream().map(this::decode).toList(),page,size);}
    public Basket expertDetail(String actor,String id){health.requireExpert(actor);var row=mapper.basket(id);if(row==null)throw notFound();return decode(row);}
    public Basket review(String actor,String id,Review input){health.requireExpert(actor);var original=expertDetail(actor,id);if(original.memberId.equals(actor))throw new AgentException(HttpStatus.FORBIDDEN,"SELF_REVIEW_FORBIDDEN","You cannot review your own basket.");
        return tx.execute(s->{lock(original.memberId);var b=owned(original.memberId,id);if(b.state!=State.REVIEW_REQUIRED||b.version!=input.expectedVersion())throw AgentException.conflict("This review is no longer current.");b.version++;b.state=input.decision()==Decision.TERMINATE?State.TERMINATED:State.STALE;b.checkoutAt=null;if(input.decision()==Decision.RETURN_FOR_ADJUSTMENT)b.checkoutCount=0;event(b,"EXPERT_"+input.decision(),"Expert "+users.findById(actor).username()+": "+input.rationale());save(b);return b;});}
    public Map<String,Object> access(String actor){var u=users.findById(actor);return Map.of("expert",health.isExpert(actor),"reportStore",u==null?"":policy.reportStores().getOrDefault(u.username(),""),"mode","SIMULATED_RETAILER","maxStoreAttempts",policy.retailer().maxAttempts());}
    public Report report(String actor){
        var u=users.findById(actor);String store=u==null?null:policy.reportStores().get(u.username());if(store==null||store.isBlank())throw new AgentException(HttpStatus.FORBIDDEN,"REPORT_ACCESS_REQUIRED","No store report is assigned to this account.");
        record Entry(String member,Line line) {}
        var entries=mapper.reportOrders().stream().map(this::decodeOrder).flatMap(o->accepted(o).stream().filter(l->l.product().facts().store().equals(store)).map(l->new Entry(o.memberId,l))).toList();
        boolean suppressed=entries.stream().map(Entry::member).distinct().count()<policy.minimumCohort();List<Demand> rows=new ArrayList<>();
        if(!suppressed){Map<String,List<Entry>> groups=new TreeMap<>();entries.forEach(e->groups.computeIfAbsent(e.line().product().productId()+"|"+e.line().product().facts().currency(),k->new ArrayList<>()).add(e));
            for(var group:groups.values())if(group.stream().map(Entry::member).distinct().count()>=policy.minimumCohort()){
                var f=group.get(0).line().product().facts();var packs=group.stream().map(e->e.line().packages()).reduce(BigDecimal.ZERO,BigDecimal::add);var value=group.stream().map(e->e.line().subtotal()).reduce(BigDecimal.ZERO,BigDecimal::add);var promoted=group.stream().filter(e->!Objects.toString(e.line().product().facts().promotion(),"").isBlank()).map(e->e.line().subtotal()).reduce(BigDecimal.ZERO,BigDecimal::add);
                rows.add(new Demand(f.sku(),f.name(),f.currency(),packs,value,promoted));
            }
        }
        return new Report(store,"SIMULATED_RETAILER",suppressed,policy.minimumCohort(),rows,"Accepted simulated store orders only, excluding cancelled orders and rejected lines. Each product cohort is independently suppressed below the threshold. Promotion values describe recorded labels, not attributed sales or settlement charges. No member identifiers or health data are included.");
    }
    // Lines the stores accepted; orders from before store submission count every confirmed line.
    private List<Line> accepted(Order o){
        if("LOCAL_CONFIRMED".equals(o.status))return o.snapshot.evaluation.lines();
        if(!Set.of(CONFIRMED,PARTIAL).contains(o.status))return List.of();
        var ids=o.storeOrders.stream().filter(x->x.status()==StoreStatus.ACCEPTED||x.status()==StoreStatus.PARTIAL).flatMap(x->x.lines().stream()).filter(StoreLine::accepted).map(StoreLine::productId).collect(Collectors.toSet());
        return o.snapshot.evaluation.lines().stream().filter(l->ids.contains(l.product().productId())).toList();
    }
    private static final BigDecimal UNLIMITED=new BigDecimal("1000000"),FIVE=BigDecimal.valueOf(5);
    private void evaluate(Basket b){evaluate(b,pantry.available(b.memberId));}
    private void evaluate(Basket b,Map<String,BigDecimal> stock){
        b.evaluation=engine.evaluate(b.source,b.settings,stock);b.state=b.evaluation.issues().isEmpty()?State.DRAFT:State.BLOCKED;
        b.minimumTotal=null;b.minimumChoice=null;
        // No silent relaxation: an over-budget basket stays blocked, but the member sees the lowest complete total and how to reach it.
        if(!b.evaluation.lines().isEmpty()&&b.evaluation.total().compareTo(b.settings.budget())>0)
            for(var mode:Fulfilment.values()){var e=engine.evaluate(b.source,variant(b.settings,UNLIMITED,Preference.ECONOMY,mode),stock);
                if(e.issues().isEmpty()&&(b.minimumTotal==null||e.total().compareTo(b.minimumTotal)<0)){b.minimumTotal=e.total();b.minimumChoice=label(mode,Preference.ECONOMY);}}
    }
    private static Settings variant(Settings s,BigDecimal budget,Preference preference,Fulfilment mode){
        return new Settings(budget,s.currency(),preference,mode,s.preferredStores(),s.excludedProductIds(),s.autoReplan(),s.exclusionList(),s.itemEditList());
    }
    private static String label(Fulfilment mode,Preference preference){
        return (mode==Fulfilment.PICKUP?"Pick-up across stores":"Delivery from one supermarket")+" · "+preference.name().charAt(0)+preference.name().substring(1).toLowerCase(Locale.ROOT);
    }
    /**
     * UC-05 step 5: the first screen is a complete draft basket. If the defaults leave meals unfilled or over budget, every fulfilment
     * mode and price-quality preference is evaluated on the same evidence; the member's own preference and mode win ties, then the lower total.
     * A budget that was never set becomes the cost of that basket rounded up; a budget the member set is never raised.
     */
    private void chooseDefault(Basket b){
        var stock=pantry.available(b.memberId);evaluate(b,stock);
        if(b.evaluation.issues().isEmpty())return;
        var base=b.settings;boolean unset=base.budget().signum()==0;
        record Option(Settings settings,Evaluation evaluation){}
        List<Option> complete=new ArrayList<>();
        // Keeping the member's preference matters most, then their fulfilment, then the total; a less preferred group is tried only when every closer one fails.
        for(int rank=0;rank<4&&complete.isEmpty();rank++)for(var mode:Fulfilment.values())for(var preference:Preference.values()){
            if((preference!=base.preference()?2:0)+(mode!=base.fulfilment()?1:0)!=rank)continue;
            var s=variant(base,unset?UNLIMITED:base.budget(),preference,mode);var e=engine.evaluate(b.source,s,stock);if(e.issues().isEmpty())complete.add(new Option(s,e));}
        if(complete.isEmpty())return;
        complete.sort(Comparator.comparing(o->o.evaluation().total()));
        var best=complete.get(0).settings();List<String> why=new ArrayList<>();
        if(best.fulfilment()!=base.fulfilment())why.add(base.fulfilment()==Fulfilment.DELIVERY?"No single supermarket could supply every item for delivery, so this draft uses pick-up across stores.":"Pick-up could not complete the basket, so this draft uses delivery.");
        if(best.preference()!=base.preference())why.add("The "+base.preference().name().toLowerCase(Locale.ROOT)+" preference could not complete the basket, so this draft uses "+best.preference().name().toLowerCase(Locale.ROOT)+".");
        if(unset){var suggested=complete.get(0).evaluation().total().divide(FIVE,0,RoundingMode.CEILING).multiply(FIVE).max(FIVE);
            best=variant(best,suggested,best.preference(),best.fulfilment());why.add("Budget suggested at "+best.currency()+" "+suggested.toPlainString()+", the cost of this complete basket rounded up. Change it any time.");}
        b.settings=best;evaluate(b,stock);b.defaultChoice=String.join(" ",why);
    }
    private void invalidate(Basket b,String reason){if(b.state==State.STALE)return;b.state=State.STALE;b.version++;b.checkoutAt=null;event(b,"INVALIDATED",reason);save(b);}
    private static final Set<State> FROZEN=Set.of(State.CHECKING,State.ORDERED,State.TERMINATED,State.REVIEW_REQUIRED);
    private void editable(Basket b,int version){if(b.version!=version||FROZEN.contains(b.state))throw AgentException.conflict("This basket changed or cannot be edited. Reload it first.");}
    // Invalidation only marks evidence as changed; settings and products stay the same, so an edit made just before it still applies.
    private static int expected(Basket b,int version){return b.state==State.STALE&&b.version==version+1&&!b.audit.isEmpty()&&b.audit.get(b.audit.size()-1).action().equals("INVALIDATED")?b.version:version;}
    private void event(Basket b,String action,String detail){b.updatedAt=clock.instant();b.audit.add(new Event(b.updatedAt,action,detail));}
    private void orderEvent(Order o,String action,String detail){o.events.add(new Event(clock.instant(),action,detail));}
    private void save(Basket b){mapper.save(b,json.writeValueAsString(b));}
    private void lock(String member){if(members.lockMember(member)==null)throw notFound();}
    private Basket owned(String member,String id){var r=mapper.basket(id);if(r==null||!r.memberId().equals(member))throw notFound();return decode(r);}
    private Basket decode(ProcurementMapper.Row r){return json.readValue(r.document(),Basket.class);}
    private Order decodeOrder(ProcurementMapper.Row r){return json.readValue(r.document(),Order.class);}
    private AgentException notFound(){return new AgentException(HttpStatus.NOT_FOUND,"NOT_FOUND","Basket or order not found.");}
}
