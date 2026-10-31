package com.nutrimarket.nutrimarket.service.procurement;

import com.nutrimarket.nutrimarket.config.ProcurementPolicy;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.model.market.MarketModels.*;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.*;
import com.nutrimarket.nutrimarket.model.procurement.ProcurementModels.*;
import com.nutrimarket.nutrimarket.service.market.MarketEngine;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Bounded deterministic search in PM-04 order: safety and nutrition, budget and stated preferences (stores, price-quality),
 * fulfilment convenience, then commercial interest. Promotion labels only break near-ties.
 */
@Service
public class BasketEngine {
    private final MarketEngine market;
    private final ProcurementPolicy policy;
    public BasketEngine(MarketEngine market,ProcurementPolicy policy){this.market=market;this.policy=policy;}
    public Evaluation evaluate(ProcurementInputPackage pack,Settings settings){return evaluate(pack,settings,Map.of());}
    /** pantry: edible grams per ingredient already at home; they are used before anything is bought. */
    public Evaluation evaluate(ProcurementInputPackage pack,Settings settings,Map<String,BigDecimal> pantry){
        validate(pack);
        var plan=pack.originalPlanningPackage();
        Map<String,List<ProductMatch>> choices=new LinkedHashMap<>();
        List<String> issues=new ArrayList<>(),unfulfilled=new ArrayList<>();int relaxed=0;
        var explicit=settings.exclusionList().stream().filter(e->!e.wasLearned()).toList();var learned=settings.exclusionList().stream().filter(Exclusion::wasLearned).toList();
        var omitted=settings.omittedIngredients();var disliked=settings.dislikedFoods();
        var removed=settings.itemEditList().stream().filter(e->e.choice()!=ItemChoice.DISLIKE_FOOD).map(e->e.productId().toString()).collect(Collectors.toSet());
        for(var requirement:plan.ingredientRequirements()){
            if(omitted.contains(requirement.ingredientId()))continue;
            var result=pack.marketIntelligenceResult().stream().filter(q->q.requirementId().equals(requirement.requirementId())).findFirst().orElseThrow();
            var group=plan.substitutionGroups().stream().filter(g->g.substitutionGroupId().equals(requirement.substitutionGroupId())).findFirst().orElseThrow();
            var anyCompliant=result.candidates().stream().filter(ProductMatch::eligible)
                .filter(m->m.hardConstraintStatus()==Compliance.COMPLIANT)
                .map(m->market.classify(plan,requirement,group,m.product(),m.confidence(),m.matchingMethod()))
                .filter(ProductMatch::eligible).filter(m->m.product().facts().currency().equals(settings.currency()))
                .filter(m->!settings.excludedProductIds().contains(UUID.fromString(m.product().productId())))
                .filter(m->!removed.contains(m.product().productId()))
                // A disliked food is never bought as a substitute; meals that plan it keep it until Agent 3 replaces them, so basket and menu agree.
                .filter(m->m.product().facts().ingredientId().equals(requirement.ingredientId())||!disliked.contains(m.product().facts().ingredientId()))
                .sorted(Comparator.comparing(m->m.product().productId())).toList();
            // Market results also carry planner-approved substitutes; they are used only when the planned food itself has no usable product.
            var plannedCompliant=anyCompliant.stream().filter(m->m.product().facts().ingredientId().equals(requirement.ingredientId())).toList();
            var compliant=plannedCompliant.isEmpty()?anyCompliant:plannedCompliant;
            var allowed=compliant.stream().filter(m->explicit.stream().noneMatch(e->excludes(e,m.product()))).toList();
            var eligible=allowed.stream().filter(m->learned.stream().noneMatch(e->excludes(e,m.product()))).toList();
            // PM-09: a learned exclusion is only a soft default and never leaves a meal without a product.
            if(eligible.isEmpty()&&!allowed.isEmpty()){eligible=allowed;relaxed++;}
            choices.put(requirement.requirementId(),eligible);
            if(eligible.isEmpty()){
                String hit=explicit.stream().filter(e->compliant.stream().anyMatch(m->excludes(e,m.product()))).map(e->e.type().name().toLowerCase(Locale.ROOT)+" "+e.value()).collect(Collectors.joining(", "));
                issues.add(requirement.description()+" / "+requirement.menuLineId()+(compliant.isEmpty()?": no compliant candidate in "+settings.currency()+". Market outcome: "+result.outcome()+".":": your exclusions ("+hit+") remove every compliant product. Remove one to continue."));
                unfulfilled.add(requirement.menuLineId());
            }
        }
        String explanation="Package quantities are pooled across meals. Surplus is not added to meal portions. "+
            (settings.fulfilment()==Fulfilment.DELIVERY?"Delivery uses one supermarket, comparing products plus its delivery fee.":pack.multiStoreComparable()?"Pick-up can combine supermarkets; each store's collection fee is included.":"Only a single store can be selected because comparable multi-store evidence is unavailable.")+" Range tiers come from catalogue evidence; promotion discounts are not inferred.";
        if(!omitted.isEmpty())explanation+=" Not purchased at your request: "+String.join(", ",new TreeSet<>(omitted))+". The meal plan is unchanged; these ingredients are not counted as pantry stock or confirmed consumption.";
        if(relaxed>0)explanation+=" Learned exclusions were not applied to "+relaxed+" requirement(s) because no other compliant product exists; learned preferences never block a meal.";
        if(!issues.isEmpty())return new Evaluation(List.of(),BigDecimal.ZERO,issues,unfulfilled.stream().distinct().toList(),false,explanation,BigDecimal.ZERO,List.of(),null,null);
        Comparator<Ranked> economy=Comparator
            .comparingInt((Ranked a)->a.total().compareTo(settings.budget())>0?1:0)
            .thenComparingInt(Ranked::unquoted)
            .thenComparing(Ranked::total)
            .thenComparingInt(Ranked::missedPreferences)
            .thenComparingInt(Ranked::storeCount)
            .thenComparing(Ranked::key);
        var first=search(pack,choices,settings,economy,a->true,pantry);boolean limited=first.limited();
        var complete=complete(first.beam(),plan,settings,economy);
        if(complete.isEmpty())return new Evaluation(List.of(),BigDecimal.ZERO,List.of(settings.fulfilment()==Fulfilment.DELIVERY?"No single supermarket can supply this delivery basket within the planning constraints. Try pick-up or adjust your selections.":"No complete basket found within the search budget, store capability and daily nutrition targets. Adjust preferences or request expert review; constraints were not relaxed."),List.of(),limited,explanation,BigDecimal.ZERO,List.of(),null,null);
        var cheapest=complete.get(0);Ranked chosen=cheapest;String quality=null;
        // Price-quality preference (PM-07): Balanced buys higher recorded range tiers within balanced-margin of the cheapest basket, Premium within the budget.
        boolean tiers=choices.values().stream().flatMap(List::stream).anyMatch(m->m.product().facts().qualityTier()!=null);
        if(settings.preference()!=Preference.ECONOMY&&tiers&&cheapest.total().compareTo(settings.budget())<=0){
            var limit=settings.preference()==Preference.PREMIUM?settings.budget():cheapest.total().multiply(BigDecimal.ONE.add(BigDecimal.valueOf(policy.balancedMargin()))).min(settings.budget());
            // A partial basket survives only if some simple completion stays within the limit: the lowest-cost basket's remaining
            // choices, or continuing each ingredient with the product the partial basket already buys (shared packages).
            var rest=cheapest.allocations();var requirements=purchaseRequirements(plan,settings);
            Predicate<Ranked> affordable=a->{if(a.total().compareTo(limit)>0||a.missedPreferences()>cheapest.missedPreferences()||a.unquoted()>cheapest.unquoted())return false;
                int k=a.allocations().size();var fallback=new ArrayList<>(a.allocations());fallback.addAll(rest.subList(k,rest.size()));
                if(ranked(fallback,settings,"").total().compareTo(limit)<=0)return true;
                Map<String,ProductMatch> current=new HashMap<>();a.allocations().forEach(x->current.put(x.plannedIngredientId(),x.evidence()));
                var continued=new ArrayList<>(a.allocations());
                for(int j=k;j<requirements.size();j++){var r=requirements.get(j);var same=current.get(r.ingredientId());
                    continued.add(same!=null&&choices.get(r.requirementId()).contains(same)?allocate(plan,r,same,continued,pantry):rest.get(j));}
                return ranked(continued,settings,"").total().compareTo(limit)<=0;};
            Comparator<Ranked> better=Comparator.comparingInt(Ranked::quality).reversed().thenComparing(Ranked::total).thenComparingInt(Ranked::storeCount).thenComparing(Ranked::key);
            var upgraded=search(pack,choices,settings,better,affordable,pantry);limited|=upgraded.limited();
            var found=complete(upgraded.beam(),plan,settings,better).stream().filter(a->a.missedPreferences()==cheapest.missedPreferences()&&a.unquoted()==cheapest.unquoted()&&a.quality()>cheapest.quality()).findFirst();
            if(found.isPresent()){chosen=found.get();quality=(settings.preference()==Preference.PREMIUM?"Premium":"Balanced")+": chose higher recorded range tiers (score "+chosen.quality()+" vs "+cheapest.quality()+" for the lowest-cost basket) for "+settings.currency()+" "+chosen.total().subtract(cheapest.total())+" more than "+settings.currency()+" "+cheapest.total()+(settings.preference()==Preference.PREMIUM?", within your budget.":", within the "+percent(policy.balancedMargin())+" Balanced margin.");}
            else quality="No higher recorded range tier fits the "+(settings.preference()==Preference.PREMIUM?"budget":percent(policy.balancedMargin())+" Balanced margin")+"; the lowest-cost basket is kept.";
        }
        var base=chosen;String decision=null;
        // PM-04: promotion labels only decide between baskets that are no worse on budget, stated preferences, fee quotes, store count and range tiers, within the near-equivalence margin.
        boolean promotions=choices.values().stream().flatMap(List::stream).anyMatch(m->!Objects.toString(m.product().facts().promotion(),"").isBlank());
        if(promotions&&policy.promotionTieMargin()>0&&base.total().compareTo(settings.budget())<=0){
            var limit=settings.preference()==Preference.ECONOMY?base.total():base.total().multiply(BigDecimal.ONE.add(BigDecimal.valueOf(policy.promotionTieMargin()))).min(settings.budget());
            if(settings.preference()==Preference.BALANCED)limit=limit.min(cheapest.total().multiply(BigDecimal.ONE.add(BigDecimal.valueOf(policy.balancedMargin()))));
            final BigDecimal promotionLimit=limit;
            // Every pruned quantity only grows as allocations are added, so partial baskets beyond these bounds can be discarded.
            Predicate<Ranked> equivalent=a->a.total().compareTo(promotionLimit)<=0&&a.missedPreferences()<=base.missedPreferences()&&a.unquoted()<=base.unquoted()&&a.storeCount()<=base.storeCount();
            Comparator<Ranked> promotional=Comparator.comparing(Ranked::promoted).reversed().thenComparing(Ranked::total).thenComparing(Ranked::key);
            var second=search(pack,choices,settings,promotional,equivalent,pantry);limited|=second.limited();
            chosen=complete(second.beam(),plan,settings,promotional).stream().filter(a->a.missedPreferences()==base.missedPreferences()&&a.unquoted()==base.unquoted()&&a.storeCount()==base.storeCount()
                &&(settings.preference()==Preference.ECONOMY||a.quality()>=base.quality())&&a.promoted().compareTo(base.promoted())>0).findFirst().orElse(base);
            if(chosen!=base)decision="Promotion tie-break: selected "+settings.currency()+" "+chosen.promoted()+" of promoted products for "+settings.currency()+" "+chosen.total().subtract(base.total())+" more than the equivalent basket without promotions, within the "+percent(policy.promotionTieMargin())+" near-equivalence margin. Budget, store preferences, fee quotes and store count were equal.";
        }
        var lines=checked(lines(chosen.allocations()),pack,omitted);
        long fromPantry=lines.stream().filter(l->l.fromPantry().signum()>0).count();
        if(fromPantry>0)explanation+=" "+fromPantry+" product(s) are partly or fully supplied from your pantry, so less is bought.";var products=cost(chosen.allocations());var fees=fees(chosen.allocations(),settings);var total=chosen.total();
        var unquoted=fees.stream().filter(f->!f.quoted()).map(StoreFee::store).toList();
        if(!unquoted.isEmpty()){explanation+=" No delivery fee is configured for "+String.join(", ",unquoted)+"; the total excludes it.";issues.add("Delivery fee is not available for "+String.join(", ",unquoted)+". Choose pick-up or a supermarket with a quoted fee.");}
        if(quality!=null)explanation+=" "+quality;
        if(decision!=null)explanation+=" "+decision;
        if(total.compareTo(settings.budget())>0)issues.add("The proposed basket exceeds your budget. Adjust the budget, exclusions or store preference; no order can be confirmed.");
        return new Evaluation(lines,total,issues,List.of(),limited,explanation+(limited?" Bounded search was used; a global minimum or infeasibility is not guaranteed.":""),products,fees,decision,quality);
    }
    public static boolean excludes(Exclusion e,Product p){var f=p.facts();String v=e.value().strip();return e.type()==ExclusionType.BRAND?f.brand().equalsIgnoreCase(v):f.sku().equalsIgnoreCase(v);}
    private static String percent(double fraction){return BigDecimal.valueOf(fraction*100).stripTrailingZeros().toPlainString()+"%";}
    // quality sums recorded range tiers per requirement: premium 2, standard 1, basic or unrecorded 0.
    record Ranked(List<Allocation> allocations,BigDecimal total,int storeCount,int missedPreferences,int unquoted,BigDecimal promoted,int quality,String key) {}
    private record Search(List<Ranked> beam,boolean limited) {}
    private List<Requirement> purchaseRequirements(PlanningPackage plan,Settings settings){return plan.ingredientRequirements().stream().filter(r->!settings.omittedIngredients().contains(r.ingredientId())).toList();}
    private Search search(ProcurementInputPackage pack,Map<String,List<ProductMatch>> choices,Settings settings,Comparator<Ranked> rank,Predicate<Ranked> keep,Map<String,BigDecimal> pantry){
        var plan=pack.originalPlanningPackage();var start=ranked(List.of(),settings,"");List<Ranked> beam=new ArrayList<>();beam.add(start);boolean limited=false;
        // Daily nutrition of each partial basket, built up one allocation at a time: only the day that changed can newly exceed a maximum.
        var dates=dates(plan);Map<Ranked,Map<LocalDate,Map<String,BigDecimal>>> totals=new IdentityHashMap<>();
        var base=omitted(plan,settings,dates);if(base==null)return new Search(List.of(),false);totals.put(start,base);
        for(var r:purchaseRequirements(plan,settings)){
            List<Ranked> expanded=new ArrayList<>();Map<Ranked,Map<LocalDate,Map<String,BigDecimal>>> next=new IdentityHashMap<>();
            for(var prior:beam)for(var candidate:choices.get(r.requirementId())){
                var allocations=prior.allocations();var allocation=allocate(plan,r,candidate,allocations,pantry);
                var added=new ArrayList<>(allocations);added.add(allocation);
                if((settings.fulfilment()==Fulfilment.DELIVERY||!pack.multiStoreComparable())&&stores(added)>1)continue;
                var daily=add(totals.get(prior),allocation,plan,dates);if(daily==null)continue;
                var ranked=ranked(added,settings,prior.key()+candidate.product().productId());if(keep.test(ranked)){expanded.add(ranked);next.put(ranked,daily);}
            }
            expanded.sort(rank);if(expanded.size()>policy.searchWidth())limited=true;
            beam=new ArrayList<>(expanded.subList(0,Math.min(policy.searchWidth(),expanded.size())));totals=next;
            if(beam.isEmpty())break;
        }
        return new Search(beam,limited);
    }
    private List<Ranked> complete(List<Ranked> beam,PlanningPackage plan,Settings settings,Comparator<Ranked> rank){
        return beam.stream().filter(a->a.allocations().size()==purchaseRequirements(plan,settings).size()&&withinTargets(a.allocations(),plan,settings,true)).sorted(rank).toList();
    }
    // Called for every partial basket the search tries, so the totals are gathered in one pass; they equal those of lines() and fees().
    private Ranked ranked(List<Allocation> a,Settings s,String key){
        Map<String,BigDecimal> sold=new HashMap<>();Map<String,ProductInput> facts=new HashMap<>();Set<String> stores=new HashSet<>();int quality=0,misses=0;
        for(var x:a){var f=x.evidence().product().facts();var id=x.evidence().product().productId();
            sold.merge(id,x.soldGrams(),BigDecimal::add);facts.putIfAbsent(id,f);quality+=tier(f.qualityTier());
            if(x.soldGrams().signum()>0){stores.add(f.store());if(!s.preferredStores().isEmpty()&&!s.preferredStores().contains(f.store()))misses++;}}
        BigDecimal total=BigDecimal.ZERO,promoted=BigDecimal.ZERO;int unquoted=0;
        for(var e:sold.entrySet()){var f=facts.get(e.getKey());var subtotal=e.getValue().divide(f.packageGrams(),0,RoundingMode.CEILING).multiply(f.price()).setScale(2,RoundingMode.HALF_UP);
            total=total.add(subtotal);if(!Objects.toString(f.promotion(),"").isBlank())promoted=promoted.add(subtotal);}
        for(String store:stores){var fee=fee(store,s);if(fee.quoted())total=total.add(fee.amount());else unquoted++;}
        return new Ranked(a,total,stores.size(),misses,unquoted,promoted,quality,key);
    }
    private static int tier(QualityTier t){return t==null?0:switch(t){case PREMIUM->2;case STANDARD->1;case BASIC->0;};}
    // One fee per store order, so splitting a basket across stores adds each store's fee.
    public List<StoreFee> fees(List<Allocation> a,Settings s){
        return bought(a).stream().map(x->x.evidence().product().facts().store()).distinct().sorted().map(store->fee(store,s)).toList();
    }
    private StoreFee fee(String store,Settings s){
        var config=policy.storeFees().stream().filter(f->f.store().equals(store)&&f.currency().equals(s.currency())).findFirst();
        var address=policy.storeAddresses().get(store);
        if(s.fulfilment()==Fulfilment.PICKUP)return new StoreFee(store,Fulfilment.PICKUP,config.map(ProcurementPolicy.StoreFees::pickup).filter(Objects::nonNull).orElse(BigDecimal.ZERO),s.currency(),true,address);
        var delivery=config.map(ProcurementPolicy.StoreFees::delivery).orElse(null);
        return new StoreFee(store,Fulfilment.DELIVERY,delivery,s.currency(),delivery!=null,address);
    }
    public void validate(ProcurementInputPackage p){
        try{
            market.validate(p.originalPlanningPackage());var source=p.originalPlanningPackage();
            if(p.schemaVersion()!=1||p.marketRevision()<1||!p.planId().equals(source.planId())||p.planVersion()!=source.planVersion()||!p.assessmentId().equals(source.assessmentId())||p.profileVersion()!=source.profileVersion()||!p.weeklyMealPlan().equals(source.weeklyMealPlan())||!p.substitutionGroups().equals(source.substitutionGroups())||p.marketIntelligenceResult().size()!=source.ingredientRequirements().size())throw new IllegalArgumentException();
            Set<String> seen=new HashSet<>();
            for(var q:p.marketIntelligenceResult()){
                var r=source.ingredientRequirements().stream().filter(x->x.requirementId().equals(q.requirementId())).findFirst().orElseThrow();
                if(!seen.add(q.requirementId())||!r.menuLineId().equals(q.menuLineId())||!r.ingredientId().equals(q.ingredientId())||r.quantity().compareTo(q.requiredGrams())!=0)throw new IllegalArgumentException();
            }
        }catch(RuntimeException e){throw AgentException.conflict("The procurement input does not match its published planning contract.");}
    }
    // Pantry stock of the product's ingredient is shared across requirements in order; only the remainder is bought.
    // A substitute product supplies the planner's substitute amount, not the planned one.
    private Allocation allocate(PlanningPackage plan,Requirement r,ProductMatch m,List<Allocation> prior,Map<String,BigDecimal> pantry){
        String ingredient=m.product().facts().ingredientId();var quantity=market.quantityFor(plan,r,ingredient);var stock=pantry.getOrDefault(ingredient,BigDecimal.ZERO);
        var used=stock.signum()<=0?BigDecimal.ZERO:prior.stream().filter(a->ingredient.equals(a.evidence().product().facts().ingredientId())).map(Allocation::fromPantry).reduce(BigDecimal.ZERO,BigDecimal::add);
        var taken=stock.subtract(used).max(BigDecimal.ZERO).min(quantity);
        return new Allocation(r.requirementId(),r.menuLineId(),quantity,sold(quantity.subtract(taken),m.product()),m,r.ingredientId(),r.substitutionGroupId(),taken.signum()>0?taken:null);
    }
    private BigDecimal sold(BigDecimal grams,Product product){var f=product.facts();return "RAW".equals(f.preparation())?grams.divide(f.cookedYieldFactor(),6,RoundingMode.HALF_UP):grams;}
    // Allocations fully supplied from the pantry need no store, so they add no store, fee or store-preference miss.
    private static List<Allocation> bought(List<Allocation> a){return a.stream().filter(x->x.soldGrams().signum()>0).toList();}
    private int stores(List<Allocation> a){return (int)bought(a).stream().map(x->x.evidence().product().facts().store()).distinct().count();}
    private BigDecimal cost(List<Allocation> a){return lines(a).stream().map(Line::subtotal).reduce(BigDecimal.ZERO,BigDecimal::add);}
    public List<Line> lines(List<Allocation> allocations){
        Map<String,List<Allocation>> groups=new TreeMap<>();
        for(var a:allocations)groups.computeIfAbsent(a.evidence().product().productId(),k->new ArrayList<>()).add(a);
        List<Line> lines=new ArrayList<>();
        for(var entries:groups.values()){
            var p=entries.get(0).evidence().product();var grams=entries.stream().map(Allocation::soldGrams).reduce(BigDecimal.ZERO,BigDecimal::add);
            var packs=grams.divide(p.facts().packageGrams(),0,RoundingMode.CEILING);var purchased=packs.multiply(p.facts().packageGrams());
            var pantry=entries.stream().map(Allocation::fromPantry).reduce(BigDecimal.ZERO,BigDecimal::add);
            lines.add(new Line(p,grams,packs,purchased,purchased.subtract(grams),packs.multiply(p.facts().price()).setScale(2,RoundingMode.HALF_UP),List.copyOf(entries),List.of(),pantry.signum()>0?pantry:null));
        }
        return lines;
    }
    // PM-02: the evidence behind every constraint check of each selected product, derived from the same facts classify() used.
    private List<Line> checked(List<Line> lines,ProcurementInputPackage pack,Set<String> omitted){
        var plan=pack.originalPlanningPackage();var c=plan.healthConstraintSet();
        return lines.stream().map(l->{
            var f=l.product().facts();List<Check> checks=new ArrayList<>();
            String declared="declared allergens: "+(f.allergens().isEmpty()?"none":String.join(", ",f.allergens()))+"; may contain: "+(f.mayContain().isEmpty()?"none":String.join(", ",f.mayContain()));
            if(c.excludedAllergens().isEmpty())checks.add(new Check("Allergies","PASS","No allergy recorded in the health profile. Complete allergen evidence on file ("+declared+")."));
            for(String a:c.excludedAllergens())checks.add(new Check("Allergy: "+a,"PASS","Not in the "+declared+" or the "+f.ingredients().size()+" listed ingredients; allergen evidence is marked complete."));
            for(String r:c.dietaryRestrictions())checks.add(new Check("Dietary restriction: "+r,"PASS","Verified dietary claims: "+String.join(", ",f.dietaryClaims())+"."));
            for(String condition:c.relevantConditions())checks.add(new Check("Health condition: "+condition,"CONTEXT","Routine condition passed to planning as context; the daily targets below are enforced."));
            for(String instruction:c.expertInstructions())checks.add(new Check("Expert instruction","CONTEXT",instruction));
            var groups=l.allocations().stream().map(Allocation::substitutionGroupId).filter(Objects::nonNull).distinct().count();
            checks.add(new Check("Substitution group","PASS","Ingredient "+f.ingredientId()+" is permitted by the planner for "+l.allocations().size()+" meal requirement(s) in "+groups+" substitution group(s)."));
            var nutrients=plan.substitutionGroups().stream().filter(g->l.allocations().stream().anyMatch(a->g.substitutionGroupId().equals(a.substitutionGroupId()))).flatMap(g->g.nutrientTolerances().keySet().stream()).distinct().sorted().toList();
            var listed=nutrients.stream().map(BasketEngine::nutrient).collect(Collectors.joining(", "));
            checks.add(new Check("Nutrient tolerance","PASS",(listed.isEmpty()?"Nutrients":Character.toUpperCase(listed.charAt(0))+listed.substring(1))+" stay within each meal's tolerance"+("RAW".equals(f.preparation())?" using cooked-yield factor "+f.cookedYieldFactor().stripTrailingZeros().toPlainString():"")+"."));
            checks.add(new Check("Daily nutrition targets",omitted.isEmpty()?"PASS":"CONTEXT",omitted.isEmpty()?"Rechecked for the whole basket: every day stays within the "+plan.nutritionTargets().keySet().stream().sorted().map(BasketEngine::nutrient).collect(Collectors.joining(", "))+" targets.":"Checked against the original meal plan, including ingredients you chose not to purchase. This partial shopping basket does not establish complete dietary intake."));
            var checkedAt=pack.marketIntelligenceResult().stream().filter(q->l.allocations().stream().anyMatch(a->a.requirementId().equals(q.requirementId()))).map(RequirementResult::checkedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            checks.add(new Check("Availability and freshness","PASS",(f.availability()==Availability.IN_STOCK?"In stock":f.availability().name())+" at "+f.store()+"; price and stock observed "+STAMP.format(f.observedAt())+(checkedAt==null?"":"; rechecked by Agent 4 at "+STAMP.format(checkedAt))+"."));
            var confidence=l.allocations().get(0).evidence().confidence();
            checks.add(new Check("Product identity","PASS","Same food and form as planned ("+l.allocations().get(0).evidence().matchingMethod()+(confidence==null?"":", confidence "+confidence.stripTrailingZeros().toPlainString())+")."));
            checks.add(new Check("Source evidence","PASS",f.sourceId()+" · "+f.sourceReference()));
            if(l.fromPantry().signum()>0)checks.add(new Check("Pantry stock","PASS",l.fromPantry().stripTrailingZeros().toPlainString()+" g ready-to-eat equivalent comes from shelf-stable stock left over from earlier orders."));
            return new Line(l.product(),l.requiredSoldGrams(),l.packages(),l.purchasedGrams(),l.surplusGrams(),l.subtotal(),l.allocations(),List.copyOf(checks),l.pantryGrams());
        }).toList();
    }
    /**
     * What changed between the basket the member reviewed and a rebuilt one: replaced products, prices, packages, fees and total (PM-08).
     * reasons optionally explains why a previous product (by ID) was replaced.
     */
    public List<MaterialChange> changes(Evaluation before,Evaluation after,ProcurementInputPackage source,Map<String,String> reasons){
        List<MaterialChange> out=new ArrayList<>();
        if(before==null||before.lines().isEmpty()||after.lines().isEmpty())return out;
        Map<String,Product> was=new HashMap<>(),now=new HashMap<>();Map<String,String> labels=new HashMap<>();
        before.lines().forEach(l->l.allocations().forEach(a->was.put(a.requirementId(),l.product())));
        after.lines().forEach(l->l.allocations().forEach(a->now.put(a.requirementId(),l.product())));
        source.originalPlanningPackage().ingredientRequirements().forEach(r->labels.put(r.requirementId(),r.ingredientId()));
        Map<String,List<String>> swaps=new TreeMap<>();
        was.forEach((requirement,old)->{var replacement=now.get(requirement);if(replacement!=null&&!replacement.productId().equals(old.productId()))swaps.computeIfAbsent(old.productId()+"|"+replacement.productId(),k->new ArrayList<>()).add(requirement);});
        for(var swap:swaps.entrySet()){
            var old=was.get(swap.getValue().get(0));var replacement=now.get(swap.getValue().get(0));
            var current=source.marketIntelligenceResult().stream().flatMap(q->q.candidates().stream()).filter(m->m.product().productId().equals(old.productId())).findFirst();
            boolean stock=current.isPresent()&&current.get().product().facts().availability()!=Availability.IN_STOCK;
            String why=reasons.containsKey(old.productId())?reasons.get(old.productId()):stock?"The previous product is now "+current.get().product().facts().availability()+".":current.isPresent()&&!current.get().eligible()?"The previous product no longer passes: "+String.join(" ",current.get().reasons()):"Rebuilt on refreshed prices and stock.";
            out.add(new MaterialChange(stock?ChangeType.STOCK:ChangeType.SUBSTITUTION,labels.get(swap.getValue().get(0))+" · "+swap.getValue().size()+" meal requirement(s)",label(old),label(replacement),why+" Same substitution group; constraints rechecked.",replacement.productId()));
        }
        Map<String,Line> oldLines=new HashMap<>();before.lines().forEach(l->oldLines.put(l.product().productId(),l));
        for(var l:after.lines()){var o=oldLines.get(l.product().productId());if(o==null)continue;var a=o.product().facts();var b=l.product().facts();
            if(a.price().compareTo(b.price())!=0)out.add(new MaterialChange(ChangeType.PRICE,label(l.product()),b.currency()+" "+a.price().setScale(2,RoundingMode.HALF_UP)+" per pack",b.currency()+" "+b.price().setScale(2,RoundingMode.HALF_UP)+" per pack","Price changed in refreshed catalogue evidence.",l.product().productId()));
            if(a.packageGrams().compareTo(b.packageGrams())!=0)out.add(new MaterialChange(ChangeType.PACKAGE,label(l.product()),a.packageGrams().stripTrailingZeros().toPlainString()+" g",b.packageGrams().stripTrailingZeros().toPlainString()+" g","Package size changed; quantities were recalculated.",l.product().productId()));
        }
        Map<String,StoreFee> oldFees=new TreeMap<>(),newFees=new TreeMap<>();Objects.requireNonNullElse(before.fees(),List.<StoreFee>of()).forEach(f->oldFees.put(f.store(),f));after.fees().forEach(f->newFees.put(f.store(),f));
        Set<String> stores=new TreeSet<>(oldFees.keySet());stores.addAll(newFees.keySet());
        for(String store:stores){var a=oldFees.get(store);var b=newFees.get(store);
            if(a==null||b==null||!Objects.equals(a.quoted(),b.quoted())||a.amount()!=null&&b.amount()!=null&&a.amount().compareTo(b.amount())!=0||(a.amount()==null)!=(b.amount()==null))
                out.add(new MaterialChange(ChangeType.FEE,store+" "+((b==null?a:b).fulfilment()==Fulfilment.PICKUP?"pick-up":"delivery"),fee(a),fee(b),a==null?"A new store order is needed.":b==null?"This store is no longer used.":"The configured fee changed.",null));
        }
        String currency=after.lines().get(0).product().facts().currency();
        if(before.total().compareTo(after.total())!=0)out.add(new MaterialChange(ChangeType.TOTAL,"Basket total",currency+" "+before.total().setScale(2,RoundingMode.HALF_UP),currency+" "+after.total().setScale(2,RoundingMode.HALF_UP),"Products plus quoted fulfilment fees.",null));
        return out;
    }
    private static final java.time.format.DateTimeFormatter STAMP=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(java.time.ZoneOffset.UTC);
    private static final Map<String,String> NUTRIENTS=Map.of("1008","energy","1003","protein","1004","fat","1005","carbohydrate","1079","fibre","1093","sodium","1087","calcium","1089","iron");
    private static String nutrient(String id){return NUTRIENTS.getOrDefault(id,"nutrient "+id);}
    private static String label(Product p){var f=p.facts();return f.brand()+" "+f.name()+" ("+f.sku()+", "+f.store()+")";}
    private static String fee(StoreFee f){return f==null?"no store order":!f.quoted()?"fee not quoted":f.amount().signum()==0?"store order, no fee":"store order, fee "+f.currency()+" "+f.amount().setScale(2,RoundingMode.HALF_UP);}
    private static Map<String,LocalDate> dates(PlanningPackage p){Map<String,LocalDate> dates=new HashMap<>();p.weeklyMealPlan().forEach(m->dates.put(m.menuLineId(),m.date()));return dates;}
    private boolean withinTargets(List<Allocation> allocations,PlanningPackage p,Settings settings,boolean complete){
        var dates=dates(p);var daily=omitted(p,settings,dates);if(daily==null)return false;
        for(var a:allocations){var amounts=contribution(a,p);if(amounts==null)return false;amounts.forEach((k,v)->daily.computeIfAbsent(dates.get(a.menuLineId()),d->new HashMap<>()).merge(k,v,BigDecimal::add));}
        if(complete&&daily.size()!=7)return false;
        for(var day:daily.values())if(!withinTargets(day,p,complete))return false;
        return true;
    }
    private static boolean withinTargets(Map<String,BigDecimal> day,PlanningPackage p,boolean complete){
        for(var t:p.nutritionTargets().entrySet()){
            var amount=day.getOrDefault(t.getKey(),BigDecimal.ZERO);
            if(amount.compareTo(t.getValue().maximum())>0||complete&&amount.compareTo(t.getValue().minimum())<0)return false;
        }
        return true;
    }
    // Omitting a purchase does not delete its meal-plan portion or fabricate pantry stock/consumption. Null when its nutrition is unusable.
    private Map<LocalDate,Map<String,BigDecimal>> omitted(PlanningPackage p,Settings settings,Map<String,LocalDate> dates){
        Map<LocalDate,Map<String,BigDecimal>> daily=new HashMap<>();
        for(var r:p.ingredientRequirements())if(settings.omittedIngredients().contains(r.ingredientId())){
            var source=p.nutritionSources().get(r.ingredientId());if(source==null)return null;
            for(var entry:p.nutritionTargets().entrySet()){
                var n=source.nutrition().per100g().get(entry.getKey());var converted=n==null?null:market.convert(n.amount(),n.unit(),entry.getValue().unit());if(converted==null)return null;
                daily.computeIfAbsent(dates.get(r.menuLineId()),k->new HashMap<>()).merge(entry.getKey(),converted.multiply(r.quantity()).divide(BigDecimal.valueOf(100)),BigDecimal::add);
            }
        }
        return daily;
    }
    // Pantry food is eaten too, so nutrition uses the full portion rather than only the bought share. Null when the label cannot be converted.
    private Map<String,BigDecimal> contribution(Allocation a,PlanningPackage p){
        Map<String,BigDecimal> amounts=new HashMap<>();
        for(var entry:p.nutritionTargets().entrySet()){
            var value=a.evidence().product().facts().per100g().get(entry.getKey());
            var converted=value==null?null:market.convert(value.amount(),value.unit(),entry.getValue().unit());if(converted==null)return null;
            amounts.put(entry.getKey(),converted.multiply(sold(a.edibleGrams(),a.evidence().product())).divide(BigDecimal.valueOf(100)));
        }
        return amounts;
    }
    /** The prior partial basket's daily totals plus one allocation, or null when that allocation's day would exceed a maximum. */
    private Map<LocalDate,Map<String,BigDecimal>> add(Map<LocalDate,Map<String,BigDecimal>> prior,Allocation a,PlanningPackage p,Map<String,LocalDate> dates){
        var amounts=contribution(a,p);if(amounts==null)return null;
        var date=dates.get(a.menuLineId());var day=new HashMap<>(prior.getOrDefault(date,Map.of()));amounts.forEach((k,v)->day.merge(k,v,BigDecimal::add));
        if(!withinTargets(day,p,false))return null;
        var next=new HashMap<>(prior);next.put(date,day);return next;
    }
}
