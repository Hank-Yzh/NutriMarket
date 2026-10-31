package com.nutrimarket.nutrimarket.service.demo;

import com.nutrimarket.nutrimarket.model.health.HealthModels.ProfileInput;
import com.nutrimarket.nutrimarket.model.health.HealthModels.Target;
import com.nutrimarket.nutrimarket.model.market.MarketModels;
import com.nutrimarket.nutrimarket.model.market.MarketModels.*;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.*;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.State;
import com.nutrimarket.nutrimarket.model.procurement.ProcurementModels.*;
import com.nutrimarket.nutrimarket.service.market.MarketService;
import com.nutrimarket.nutrimarket.service.planning.PlanningService;
import com.nutrimarket.nutrimarket.service.procurement.ProcurementService;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic demo scenarios from demo-scenarios.json. A username only selects input data: profile constraints and a
 * pre-validated meal plan; market evidence is the synthetic test catalogue. Plans, matches, baskets and orders come from the
 * real Agent 3-5 services, and verify() derives PASS or FAIL from their actual output.
 */
@Service
public class DemoScenarios {
    public record Member(String code,String username,String scenario,String plan,List<String> allergies,List<String> restrictions,String expect,String purpose) {}
    public record WeekPlan(String description,List<CandidateMeal> meals) {}
    public record Profile(String timezone,String goals,List<String> conditions,String preferences,List<String> mealStyles,String targetSource,Map<String,Target> targets) {}
    public record Fixture(String about,Profile profile,LocalDate assessmentWeek,Map<String,WeekPlan> plans,List<Member> members) {}
    public record Check(String description,boolean passed,String evidence) {}
    public record Run(Member member,Plan plan,MarketResult market,Basket basket,List<Check> checks) {
        public boolean passed(){return checks.stream().allMatch(Check::passed);}
    }
    static final String REPORT_STORE="FreshMart Central";
    private final PlanningService planning;private final MarketService market;private final ProcurementService procurement;private final Fixture fixture;
    public DemoScenarios(PlanningService planning,MarketService market,ProcurementService procurement,ObjectMapper json) {
        this.planning=planning;this.market=market;this.procurement=procurement;
        try(var in=new ClassPathResource("demo-scenarios.json").getInputStream()){fixture=json.readValue(in,Fixture.class);}
        catch(IOException e){throw new IllegalStateException("Cannot read demo-scenarios.json",e);}
    }
    public Fixture fixture(){return fixture;}
    public Member member(String username){return fixture.members().stream().filter(m->m.username().equals(username)).findFirst().orElseThrow();}
    /** The shared demo profile with this member's own allergies and restrictions, and standing consent to automatic planning. */
    public ProfileInput profile(Member m,long expectedVersion) {
        var p=fixture.profile();
        return new ProfileInput(expectedVersion,p.timezone(),p.goals(),m.allergies(),m.restrictions(),p.conditions(),p.preferences(),p.targetSource(),p.targets(),true,null,true,p.mealStyles());
    }
    /** Agent 3 validates and publishes the fixed week, Agent 4 matches it and Agent 5 builds the basket. */
    public Run run(String memberId,Member m,String assessmentId,LocalDate weekStart) {
        var plan=planning.generateFromFixture(memberId,new Generate(UUID.fromString(assessmentId),weekStart,true),new Candidate(fixture.plans().get(m.plan()).meals()),m.plan());
        if(plan.state==State.DRAFT)plan=planning.publish(memberId,plan.planId,new Publish(plan.version,true));
        if(plan.state!=State.PUBLISHED)throw new IllegalStateException(m.code()+" plan stopped at "+plan.state+": "+plan.issues);
        var result=market.start(memberId,new MarketModels.Start(UUID.fromString(plan.planId),plan.version));
        // A rerun finds earlier evidence stale after a catalogue re-stamp: re-check it as Procurement does before ordering,
        // then let the member's own save path recalculate the existing basket with its unchanged settings.
        if(!result.planStale&&Set.of(MarketModels.State.STALE,MarketModels.State.INTERRUPTED).contains(result.state))
            result=market.refreshBeforeOrder(memberId,result.resultId,new Refresh(UUID.randomUUID(),result.revision,result.planning.weeklyMealPlan().stream().map(Meal::menuLineId).toList(),UUID.fromString(plan.planId),null,null));
        var basket=procurement.receive(memberId,result.resultId,result.revision);
        if(basket.state==com.nutrimarket.nutrimarket.model.procurement.ProcurementModels.State.STALE)basket=procurement.change(memberId,basket.basketId,new Change(basket.version,basket.settings));
        result=market.get(memberId,result.resultId);
        return new Run(m,plan,result,basket,verify(m,result,basket));
    }
    /** Places the basket's order with the report store preferred, as a member would from the basket page; an existing order is returned. */
    public Order order(String memberId,Basket basket) {
        if(basket.orderId!=null)return procurement.order(memberId,basket.orderId);
        var s=basket.settings;
        var changed=procurement.change(memberId,basket.basketId,new Change(basket.version,new Settings(s.budget(),s.currency(),Preference.BALANCED,Fulfilment.PICKUP,List.of(REPORT_STORE),List.of())));
        var submitted=procurement.submit(memberId,changed.basketId,new Checkout(changed.version,UUID.randomUUID()));
        if(submitted.order()!=null)return submitted.order();
        var ready=submitted.basket();
        if(ready.state!=com.nutrimarket.nutrimarket.model.procurement.ProcurementModels.State.CHECKOUT_READY)throw new IllegalStateException("Basket "+ready.basketId+" could not be ordered: "+ready.evaluation.issues());
        return procurement.confirm(memberId,ready.basketId,new Confirm(ready.version,UUID.randomUUID(),true));
    }
    public List<Check> verifyOrder(Order o) {
        long store=o.storeOrders.stream().filter(x->x.store().equals(REPORT_STORE)&&x.status()==StoreStatus.ACCEPTED).count();
        return List.of(check("The simulated stores accepted the order",Set.of("CONFIRMED","PARTIALLY_FULFILLED").contains(o.status),"Order "+o.orderId+" is "+o.status+"."),
            check("Part of the order goes to "+REPORT_STORE+" for the store demand report",store>0,store+" accepted store order(s) at "+REPORT_STORE+"."));
    }

    /** Assertions for the scenario's expected behaviour, computed from Agent 4's evidence and Agent 5's basket. */
    public List<Check> verify(Member m,MarketResult r,Basket b) {
        List<Check> checks=new ArrayList<>();
        switch(m.expect()) {
            case "ALL_MATCHED","ORDER_PLACED" -> {
                long ready=r.requirements.stream().filter(q->q.candidates().stream().anyMatch(ProductMatch::eligible)).count();
                checks.add(check("Agent 4 found an eligible product for every requirement",ready==r.requirements.size(),ready+" of "+r.requirements.size()+" requirements ("+ingredients(r.requirements)+" ingredients)."));
                checks.add(complete(b));
            }
            case "UNSAFE_EXCLUDED" -> {
                var unknown=skus(r,x->x.hardConstraintStatus()==Compliance.UNKNOWN&&!x.product().facts().allergensComplete());
                var blocked=skus(r,x->x.hardConstraintStatus()==Compliance.NON_COMPLIANT&&x.reasons().stream().anyMatch(y->y.startsWith("Declared allergen or cross-contact")));
                checks.add(check("Agent 4 marked SKUs without an allergen statement UNKNOWN",!unknown.isEmpty(),String.join(", ",unknown)));
                checks.add(check("Agent 4 blocked SKUs that declare the member's allergens ("+String.join(", ",m.allergies())+")",!blocked.isEmpty(),String.join(", ",blocked)));
                var unsafe=new TreeSet<>(unknown);unsafe.addAll(blocked);unsafe.retainAll(selected(b));
                checks.add(check("Agent 5 selected none of those SKUs",unsafe.isEmpty(),unsafe.isEmpty()?"None of them is in the basket.":"Selected: "+String.join(", ",unsafe)));
                checks.add(complete(b));
            }
            case "SUBSTITUTED" -> {
                var missing=r.requirements.stream().filter(q->q.candidates().stream().noneMatch(x->planned(q,x)&&x.eligible())&&q.candidates().stream().anyMatch(x->planned(q,x)&&x.outcome()==Outcome.UNAVAILABLE)).toList();
                checks.add(check("Agent 4 found the planned food out of stock",!missing.isEmpty(),missing.size()+" meal lines: "+ingredients(missing)+"."));
                var covered=missing.stream().filter(q->q.candidates().stream().anyMatch(x->!planned(q,x)&&x.eligible())).toList();
                checks.add(check("Agent 4 found an eligible planner-approved substitute for each",!missing.isEmpty()&&covered.size()==missing.size(),
                    covered.size()+" of "+missing.size()+": "+missing.stream().flatMap(q->q.candidates().stream().filter(x->!planned(q,x)&&x.eligible())).map(x->x.product().facts().sku()).distinct().sorted().collect(Collectors.joining(", "))+"."));
                var bought=allocations(b);var swapped=missing.stream().filter(q->{var group=group(r,q);var a=bought.get(q.requirementId());
                    return a!=null&&group!=null&&group.substitute(a.evidence().product().facts().ingredientId())!=null;}).toList();
                checks.add(check("Agent 5 bought a substitute from the planner's group for each",!missing.isEmpty()&&swapped.size()==missing.size(),
                    swapped.size()+" of "+missing.size()+": "+swapped.stream().map(q->q.ingredientId()+" -> "+bought.get(q.requirementId()).evidence().product().facts().ingredientId()).distinct().sorted().collect(Collectors.joining(", "))+"."));
                checks.add(complete(b));
            }
            case "REPLAN_REQUIRED" -> {
                var blocked=r.requirements.stream().filter(q->q.candidates().stream().noneMatch(ProductMatch::eligible)).toList();
                checks.add(check("Agent 4 found no eligible product or substitute for some meals",!blocked.isEmpty(),blocked.size()+" meal lines: "+ingredients(blocked)+"."));
                var checked=blocked.stream().filter(q->{var g=group(r,q);return g!=null&&!g.substituteList().isEmpty()&&q.checkedSubstitutes()!=null&&q.checkedSubstitutes().containsAll(g.substituteList().stream().map(Substitute::ingredientId).toList());}).toList();
                checks.add(check("Agent 4 checked every planner-approved substitute for them",!blocked.isEmpty()&&checked.size()==blocked.size(),
                    blocked.stream().map(q->q.ingredientId()+" -> "+String.join("/",Objects.requireNonNullElse(q.checkedSubstitutes(),List.of()))).distinct().sorted().collect(Collectors.joining(", "))+"."));
                boolean current=blocked.stream().noneMatch(q->q.truncated()||Set.of(Outcome.SOURCE_FAILURE,Outcome.MODEL_FAILURE,Outcome.STALE).contains(q.outcome()));
                checks.add(check("The evidence is complete and current, so a ReplanRequest is justified",!blocked.isEmpty()&&current,
                    blocked.stream().map(q->q.outcome().name()).distinct().sorted().collect(Collectors.joining(", "))));
                var lines=blocked.stream().map(RequirementResult::menuLineId).collect(Collectors.toCollection(TreeSet::new));
                boolean exact=b.state==com.nutrimarket.nutrimarket.model.procurement.ProcurementModels.State.BLOCKED&&new TreeSet<>(b.evaluation.unfulfilledMenuLines()).equals(lines);
                checks.add(check("Agent 5 blocked the basket on exactly those meals",exact,"Basket "+b.state+"; unfulfilled "+b.evaluation.unfulfilledMenuLines()+"."));
            }
            default -> throw new IllegalArgumentException("Unknown expectation "+m.expect());
        }
        // Every scenario: whatever Agent 5 selected must be eligible, compliant evidence from Agent 4.
        var unsafe=b.evaluation.lines().stream().flatMap(l->l.allocations().stream()).filter(a->!a.evidence().eligible()||a.evidence().hardConstraintStatus()!=Compliance.COMPLIANT).map(a->a.evidence().product().facts().sku()).distinct().toList();
        checks.add(check("Every SKU Agent 5 selected is eligible and COMPLIANT",unsafe.isEmpty(),unsafe.isEmpty()?selected(b).size()+" SKUs selected.":"Not compliant: "+String.join(", ",unsafe)));
        return checks;
    }
    private static Check check(String description,boolean passed,String evidence){return new Check(description,passed,evidence);}
    private static Check complete(Basket b) {
        var e=b.evaluation;boolean done=!e.lines().isEmpty()&&e.unfulfilledMenuLines().isEmpty()&&e.issues().isEmpty();
        return check("Agent 5 built a complete basket",done,done?e.lines().size()+" products, total "+e.total()+".":"Unfulfilled "+e.unfulfilledMenuLines()+"; issues "+e.issues()+".");
    }
    private static boolean planned(RequirementResult q,ProductMatch x){return q.ingredientId().equals(x.product().facts().ingredientId());}
    private static String ingredients(List<RequirementResult> q){return q.stream().map(RequirementResult::ingredientId).distinct().sorted().collect(Collectors.joining(", "));}
    private static SortedSet<String> skus(MarketResult r,Predicate<ProductMatch> filter) {
        return r.requirements.stream().flatMap(q->q.candidates().stream()).filter(filter).map(x->x.product().facts().sku()).collect(Collectors.toCollection(TreeSet::new));
    }
    private static Set<String> selected(Basket b){return b.evaluation.lines().stream().map(l->l.product().facts().sku()).collect(Collectors.toSet());}
    private static Map<String,Allocation> allocations(Basket b) {
        Map<String,Allocation> out=new HashMap<>();b.evaluation.lines().forEach(l->l.allocations().forEach(a->out.put(a.requirementId(),a)));return out;
    }
    private static SubstitutionGroup group(MarketResult r,RequirementResult q) {
        var id=r.planning.ingredientRequirements().stream().filter(x->x.requirementId().equals(q.requirementId())).map(Requirement::substitutionGroupId).findFirst().orElse(null);
        return r.planning.substitutionGroups().stream().filter(g->g.substitutionGroupId().equals(id)).findFirst().orElse(null);
    }
}
