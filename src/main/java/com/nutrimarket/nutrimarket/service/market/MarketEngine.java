package com.nutrimarket.nutrimarket.service.market;

import com.nutrimarket.nutrimarket.config.MarketPolicy;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.model.market.MarketModels.*;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.*;
import com.nutrimarket.nutrimarket.service.health.HealthEngine;
import com.nutrimarket.nutrimarket.service.planning.IngredientCatalog;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MarketEngine {
    private static final BigDecimal KJ_PER_KCAL=new BigDecimal("4.184");
    private static final Map<String,BigDecimal> MASS=Map.of("g",BigDecimal.ONE,"mg",new BigDecimal("0.001"),"ug",new BigDecimal("0.000001"),"µg",new BigDecimal("0.000001"));
    private final MarketPolicy policy;private final IngredientCatalog catalog;private final Clock clock;private final HealthEngine health;
    public MarketEngine(MarketPolicy policy,IngredientCatalog catalog,Clock clock,HealthEngine health){this.policy=policy;this.catalog=catalog;this.clock=clock;this.health=health;}
    public Requirement purchaseRequirement(Requirement r){return catalog.purchaseRequirement(r);}
    public String purchaseName(String ingredientId){return catalog.purchaseName(ingredientId);}
    private static SubstitutionGroup group(PlanningPackage plan,Requirement r){return plan.substitutionGroups().stream().filter(g->g.substitutionGroupId().equals(r.substitutionGroupId())).findFirst().orElse(null);}
    /** Edible grams to buy for this requirement when supplied by the given ingredient: the planned amount, or the planner's substitute amount. */
    public BigDecimal quantityFor(PlanningPackage plan,Requirement r,String ingredientId){
        if(ingredientId.equals(r.ingredientId()))return r.quantity();var g=group(plan,r);var s=g==null?null:g.substitute(ingredientId);return s==null?r.quantity():s.quantity();
    }
    private static Map<String,Tolerance> tolerancesFor(SubstitutionGroup g,Requirement r,String ingredientId){
        if(ingredientId.equals(r.ingredientId()))return g.nutrientTolerances();var s=g.substitute(ingredientId);return s==null?g.nutrientTolerances():s.nutrientTolerances();
    }
    public void validate(PlanningPackage p){
        try {
            if(p.schemaVersion()!=1||p.planVersion()<1||p.profileVersion()<1||p.assessmentRevision()<1||p.publishedAt()==null||p.weeklyMealPlan().size()!=21||p.ingredientRequirements().isEmpty()||p.healthConstraintSet()==null)throw new IllegalArgumentException();
            UUID.fromString(p.memberId());UUID.fromString(p.planId());UUID.fromString(p.assessmentId());
            Set<String> lines=new HashSet<>(),requirements=new HashSet<>(),groupIds=new HashSet<>();Map<String,SubstitutionGroup> groups=new HashMap<>();
            for(var meal:p.weeklyMealPlan())if(!lines.add(meal.menuLineId()))throw new IllegalArgumentException();
            for(var g:p.substitutionGroups())if(!groupIds.add(g.substitutionGroupId())||!lines.contains(g.menuLineId())||g.allowedIngredientIds().isEmpty()||g.allowedIngredientIds().stream().anyMatch(id->catalog.get(id)==null)||g.nutrientTolerances().isEmpty())throw new IllegalArgumentException();else groups.put(g.substitutionGroupId(),g);
            for(var r:p.ingredientRequirements()){
                var g=groups.get(r.substitutionGroupId());
                if(!requirements.add(r.requirementId())||!lines.contains(r.menuLineId())||r.quantity().signum()<=0||!"g edible ready-to-eat weight".equals(r.unit())||g==null||!g.menuLineId().equals(r.menuLineId())||!g.allowedIngredientIds().contains(r.ingredientId())||r.quantity().compareTo(g.quantity())!=0||!r.unit().equals(g.unit())||!g.nutrientTolerances().keySet().containsAll(p.nutritionTargets().keySet()))throw new IllegalArgumentException();
                for(var t:g.nutrientTolerances().values())if(t.minimum().signum()<0||t.minimum().compareTo(t.maximum())>0||t.unit()==null)throw new IllegalArgumentException();
                for(var s:g.substituteList())if(!g.allowedIngredientIds().contains(s.ingredientId())||s.quantity()==null||s.quantity().signum()<=0||!s.nutrientTolerances().keySet().containsAll(p.nutritionTargets().keySet()))throw new IllegalArgumentException();
            }
            if(!p.nutritionTargets().equals(p.healthConstraintSet().dailyTargets()))throw new IllegalArgumentException();
            for(var meal:p.weeklyMealPlan()){
                var reqs=p.ingredientRequirements().stream().filter(r->r.menuLineId().equals(meal.menuLineId())).toList();
                if(reqs.size()!=meal.ingredients().size())throw new IllegalArgumentException();
                for(var part:meal.ingredients())if(reqs.stream().filter(r->r.ingredientId().equals(part.ingredientId())&&r.quantity().compareTo(part.grams())==0).count()!=1)throw new IllegalArgumentException();
            }
            if(!new HashSet<>(p.ingredientRequirements().stream().map(Requirement::menuLineId).toList()).equals(lines))throw new IllegalArgumentException();
        }catch(RuntimeException e){throw new AgentException(org.springframework.http.HttpStatus.CONFLICT,"INVALID_PLANNING_PACKAGE","Inconsistent planning identifiers, requirements or substitution groups. Republish a valid plan.");}
    }
    private String normal(String s){return s.toLowerCase(Locale.ROOT).trim().replaceAll("[-_ ]+"," ");}
    private String allergen(String s){return switch(normal(s)){case "peanuts"->"peanut";case "dairy","lactose"->"milk";case "eggs"->"egg";case "soya","soybeans"->"soy";case "wheat"->"gluten";case "nuts","tree nut"->"tree nuts";default->normal(s);};}
    public boolean expired(Product p){return p.facts().observedAt()==null||p.facts().observedAt().isAfter(clock.instant().plusSeconds(60))||p.facts().observedAt().plus(policy.maxAge()).isBefore(clock.instant());}
    public ProductMatch classify(PlanningPackage plan,Requirement r,SubstitutionGroup group,Product product,BigDecimal confidence,String method){
        var f=product.facts();List<String> unknown=new ArrayList<>(),violations=new ArrayList<>(),reasons=new ArrayList<>();
        if(!f.enabled())violations.add("The product is disabled.");
        if(!group.allowedIngredientIds().contains(f.ingredientId()))violations.add("Ingredient is outside the planner's permitted substitution group.");
        var ingredient=catalog.get(f.ingredientId());if(ingredient==null||!catalog.allowed(ingredient,plan.healthConstraintSet()))violations.add("Ingredient conflicts with recognised recipe restrictions.");
        boolean yieldKnown="RAW".equals(f.preparation())&&f.cookedYieldFactor()!=null;
        if(!"READY_TO_EAT".equals(f.preparation())&&!yieldKnown)unknown.add("Raw or unknown preparation cannot be compared with ready-to-eat requirements without a verified cooked-yield factor.");
        // Grams as sold that provide the required edible quantity; label nutrition refers to this form.
        // A planner substitute is bought in its own amount and checked against its own tolerances.
        var edible=quantityFor(plan,r,f.ingredientId());var tolerances=tolerancesFor(group,r,f.ingredientId());
        BigDecimal sold=yieldKnown?edible.divide(f.cookedYieldFactor(),6,RoundingMode.HALF_UP):edible;
        if(!f.evidenceConfirmed()||f.sourceReference()==null||f.sourceReference().isBlank())unknown.add("Source evidence is not confirmed.");
        if(!f.ingredientsComplete()||f.ingredients().isEmpty())unknown.add("Full ingredient composition is missing.");
        // Step 2 after the name match: the ingredient list must show the product really is this food, not just named after it.
        else{var problem=catalog.compositionProblem(f.ingredientId(),f.ingredients());if(problem!=null)violations.add(problem);}
        for(String food:catalog.excludedFoods(plan.healthConstraintSet()))if(catalog.contains(food,f.ingredients()))violations.add("Contains "+catalog.purchaseName(food).toLowerCase(Locale.ROOT)+", which your allergies or dietary restrictions exclude.");
        if(!f.allergensComplete())unknown.add("Complete allergen and may-contain evidence is missing.");
        var allergens=new HashSet<String>();f.allergens().forEach(a->allergens.add(allergen(a)));f.mayContain().forEach(a->allergens.add(allergen(a)));
        for(String a:plan.healthConstraintSet().excludedAllergens())if(allergens.contains(allergen(a))||f.ingredients().stream().anyMatch(i->allergen(i).equals(allergen(a))))violations.add("Declared allergen or cross-contact: "+a);
        unknown.addAll(catalog.manualChecks(plan.healthConstraintSet(),health.conditionsRequiringReview(plan.healthConstraintSet().relevantConditions())));
        var claims=new HashSet<String>();f.dietaryClaims().forEach(c->claims.add(normal(c)));if(claims.contains("vegan")){claims.add("vegetarian");claims.add("pescatarian");}if(claims.contains("vegetarian"))claims.add("pescatarian");
        // A restriction that names a food ("apple") is checked against the ingredient list above, not against label claims.
        for(String restriction:plan.healthConstraintSet().dietaryRestrictions())if(catalog.ingredientNamed(restriction)==null&&!claims.contains(normal(restriction)))unknown.add("Missing verified dietary claim: "+restriction);
        for(var entry:tolerances.entrySet()){
            var nutrient=f.per100g().get(entry.getKey());var tolerance=entry.getValue();
            var value=nutrient==null||nutrient.amount()==null||nutrient.amount().signum()<0||nutrient.unit()==null?null:convert(nutrient.amount(),nutrient.unit(),tolerance.unit());
            if(value==null)unknown.add("Missing or incompatible "+HealthEngine.NAMES.getOrDefault(entry.getKey(),"nutrient "+entry.getKey()).toLowerCase(Locale.ROOT)+" data.");
            else{var actual=value.multiply(sold).divide(BigDecimal.valueOf(100));if(actual.compareTo(tolerance.minimum())<0||actual.compareTo(tolerance.maximum())>0)violations.add(HealthEngine.NAMES.getOrDefault(entry.getKey(),"Nutrient "+entry.getKey())+" is outside the planner's substitution tolerance.");}
        }
        Compliance compliance=!violations.isEmpty()?Compliance.NON_COMPLIANT:!unknown.isEmpty()?Compliance.UNKNOWN:Compliance.COMPLIANT;
        reasons.addAll(violations);reasons.addAll(unknown);
        boolean economic=f.price()!=null&&f.price().signum()>=0&&f.packageGrams()!=null&&f.packageGrams().signum()>0&&f.currency()!=null&&f.currency().matches("[A-Z]{3}")&&f.store()!=null&&!f.store().isBlank();
        Outcome outcome;
        if(expired(product)){outcome=Outcome.STALE;reasons.add("Price, availability or product evidence is stale or future-dated.");}
        else if(compliance==Compliance.NON_COMPLIANT)outcome=Outcome.CONSTRAINT_BLOCKED;
        else if(compliance==Compliance.UNKNOWN||!economic||f.availability()==Availability.UNKNOWN){outcome=Outcome.INCOMPLETE;if(!economic)reasons.add("Price, package weight, currency or store is missing.");if(f.availability()==Availability.UNKNOWN)reasons.add("Availability is unknown.");}
        else if(f.availability()==Availability.OUT_OF_STOCK){outcome=Outcome.UNAVAILABLE;reasons.add("The product is out of stock.");}
        else if(confidence==null||confidence.compareTo(BigDecimal.valueOf(policy.confidenceThreshold()))<0){outcome=Outcome.LOW_CONFIDENCE;reasons.add("Semantic confidence is below the configured threshold: not confirmed as the same food, cut or form as the requirement.");}
        else outcome=Outcome.MATCHED;
        BigDecimal unit=economic?f.price().multiply(BigDecimal.valueOf(100)).divide(f.packageGrams(),4,RoundingMode.HALF_UP):null;
        BigDecimal packs=economic?sold.divide(f.packageGrams(),0,RoundingMode.CEILING):null;
        return new ProductMatch(product,confidence,method,compliance,outcome==Outcome.MATCHED,outcome,reasons,unit,packs,economic?packs.multiply(f.price()).setScale(2,RoundingMode.HALF_UP):null);
    }
    /** Converts label units (including kJ energy) to the planner's unit; null when not convertible. */
    public BigDecimal convert(BigDecimal amount,String from,String to){
        String a=from.toLowerCase(Locale.ROOT).strip(),b=to.toLowerCase(Locale.ROOT).strip();
        if(a.equals(b))return amount;
        if(a.equals("kj")&&b.equals("kcal"))return amount.divide(KJ_PER_KCAL,6,RoundingMode.HALF_UP);
        if(a.equals("kcal")&&b.equals("kj"))return amount.multiply(KJ_PER_KCAL);
        if(MASS.containsKey(a)&&MASS.containsKey(b))return amount.multiply(MASS.get(a)).divide(MASS.get(b),9,RoundingMode.HALF_UP);
        return null;
    }
    /** Applies a freshness time required by Procurement (1.1.6); older evidence becomes ineligible STALE. */
    public ProductMatch requireFreshness(ProductMatch m,Instant after){
        var observed=m.product().facts().observedAt();
        if(after==null||m.outcome()==Outcome.STALE||observed!=null&&!observed.isBefore(after))return m;
        var reasons=new ArrayList<>(m.reasons());reasons.add("Evidence was observed before the freshness time required by Procurement.");
        return new ProductMatch(m.product(),m.confidence(),m.matchingMethod(),m.hardConstraintStatus(),false,Outcome.STALE,reasons,m.unitPricePer100g(),m.packagesToCover(),m.costToCover());
    }
    /**
     * Package-based weekly comparison: each product is priced in whole packages for the week's total requirement of its ingredient
     * (e.g. 1.5 kg from 1 kg packs is two packs). A product is a weekly option only if it is eligible for every menu line using that
     * ingredient. Store totals use each store's cheapest eligible option per ingredient and list uncovered ingredients instead of pricing them.
     */
    public PriceComparison compare(MarketResult r){
        Map<String,List<RequirementResult>> byIngredient=new TreeMap<>();
        for(var q:r.requirements)byIngredient.computeIfAbsent(q.ingredientId(),k->new ArrayList<>()).add(q);
        List<IngredientComparison> ingredients=new ArrayList<>();
        for(var entry:byIngredient.entrySet()){
            var lines=entry.getValue();BigDecimal planned=lines.stream().map(RequirementResult::requiredGrams).reduce(BigDecimal.ZERO,BigDecimal::add),edible;
            Map<String,List<ProductMatch>> byProduct=new LinkedHashMap<>();
            for(var q:lines)for(var m:q.candidates())byProduct.computeIfAbsent(m.product().productId(),k->new ArrayList<>()).add(m);
            List<ComparisonOption> options=new ArrayList<>();
            for(var matches:byProduct.values()){
                var f=matches.get(0).product().facts();
                // A substitute's weekly amount is the planner's substitute grams for each meal it would replace.
                if(!f.ingredientId().equals(entry.getKey()))edible=matches.stream().map(m->{var q=lines.stream().filter(x->x.candidates().contains(m)).findFirst().orElseThrow();
                    var req=r.planning.ingredientRequirements().stream().filter(x->x.requirementId().equals(q.requirementId())).findFirst().orElseThrow();return quantityFor(r.planning,req,f.ingredientId());}).reduce(BigDecimal.ZERO,BigDecimal::add);
                else edible=lines.stream().map(RequirementResult::requiredGrams).reduce(BigDecimal.ZERO,BigDecimal::add);
                // A substitute is judged on the meals the planner approved it for; the planned food on every meal that uses it.
                boolean substitute=!f.ingredientId().equals(entry.getKey());
                long approved=substitute?lines.stream().filter(q->r.planning.substitutionGroups().stream().anyMatch(g->g.menuLineId().equals(q.menuLineId())&&g.substitute(f.ingredientId())!=null&&r.planning.ingredientRequirements().stream().anyMatch(x->x.requirementId().equals(q.requirementId())&&x.substitutionGroupId().equals(g.substitutionGroupId())))).count():lines.size();
                boolean everyLine=approved>0&&matches.size()==approved&&matches.stream().allMatch(ProductMatch::eligible);
                var worst=matches.stream().filter(m->!m.eligible()).findFirst().orElse(matches.get(0));
                List<String> reasons=new ArrayList<>(everyLine?List.of():worst.reasons());
                if(!f.ingredientId().equals(entry.getKey()))reasons.add(0,"Substitute from the meal plan: "+catalog.purchaseName(f.ingredientId()).toLowerCase(Locale.ROOT)+" instead of "+catalog.purchaseName(entry.getKey()).toLowerCase(Locale.ROOT)+".");
                if(substitute&&approved<lines.size())reasons.add("Approved by the meal planner for "+approved+" of "+lines.size()+" meals.");
                if(matches.size()<approved)reasons.add("Not a candidate for every menu line that uses this ingredient.");
                BigDecimal sold="RAW".equals(f.preparation())&&f.cookedYieldFactor()!=null?edible.divide(f.cookedYieldFactor(),2,RoundingMode.HALF_UP):edible;
                boolean economic=f.price()!=null&&f.packageGrams()!=null&&f.packageGrams().signum()>0;
                BigDecimal packs=economic?sold.divide(f.packageGrams(),0,RoundingMode.CEILING):null;
                options.add(new ComparisonOption(matches.get(0).product().productId(),f.sku(),f.name(),f.brand(),f.store(),f.currency(),f.price(),
                    economic?f.price().multiply(BigDecimal.valueOf(1000)).divide(f.packageGrams(),2,RoundingMode.HALF_UP):null,f.packageGrams(),sold,packs,
                    economic?packs.multiply(f.price()).setScale(2,RoundingMode.HALF_UP):null,economic?packs.multiply(f.packageGrams()).subtract(sold):null,
                    worst.hardConstraintStatus(),everyLine,everyLine?Outcome.MATCHED:worst.outcome(),reasons,f.dietaryClaims(),f.availability(),
                    f.promotion(),f.sourceId(),f.sourceReference(),f.observedAt(),f.ingredientId(),
                    matches.stream().map(ProductMatch::confidence).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null),matches.get(0).matchingMethod()));
            }
            options.sort(Comparator.comparing((ComparisonOption o)->!o.eligible()).thenComparing(ComparisonOption::totalCost,Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(ComparisonOption::productId));
            ingredients.add(new IngredientComparison(entry.getKey(),catalog.purchaseName(entry.getKey()),planned,lines.size(),options));
        }
        Set<List<String>> storeKeys=new TreeSet<>(Comparator.comparing((List<String> k)->k.get(0)).thenComparing(k->k.get(1)));
        ingredients.forEach(i->coverage(i).forEach(o->storeKeys.add(List.of(o.store(),o.currency()))));
        List<StoreTotal> stores=new ArrayList<>();
        for(var key:storeKeys){
            BigDecimal total=BigDecimal.ZERO;List<String> missing=new ArrayList<>();
            for(var i:ingredients){
                var cheapest=coverage(i).stream().filter(o->o.totalCost()!=null&&o.store().equals(key.get(0))&&o.currency().equals(key.get(1))).findFirst();
                if(cheapest.isPresent())total=total.add(cheapest.get().totalCost());else missing.add(i.name());
            }
            stores.add(new StoreTotal(key.get(0),key.get(1),total,ingredients.size()-missing.size(),ingredients.size(),missing));
        }
        stores.sort(Comparator.comparingInt((StoreTotal s)->s.missingIngredients().size()).thenComparing(StoreTotal::weeklyTotal));
        return new PriceComparison(ingredients,stores,clock.instant());
    }
    // Store coverage counts the planned food; substitutes count only for ingredients whose planned food has no eligible product.
    private static List<ComparisonOption> coverage(IngredientComparison i){
        var planned=i.options().stream().filter(o->o.eligible()&&(o.ingredientId()==null||o.ingredientId().equals(i.ingredientId()))).toList();
        return planned.isEmpty()?i.options().stream().filter(ComparisonOption::eligible).toList():planned;
    }
    public RequirementResult result(Requirement r,List<ProductMatch> matches,List<String> messages,boolean truncated){return result(r,matches,messages,truncated,null);}
    public RequirementResult result(Requirement r,List<ProductMatch> matches,List<String> messages,boolean truncated,List<String> checkedSubstitutes){
        // Without any eligible candidate, the outcome describes the planned food itself, so replanning reasons are unchanged by substitute evidence.
        // Products whose ingredient list shows they are not that food (salmon crackers) say nothing about its availability.
        var planned=matches.stream().filter(m->r.ingredientId().equals(m.product().facts().ingredientId())).toList();
        var genuine=planned.stream().filter(m->catalog.compositionProblem(r.ingredientId(),m.product().facts().ingredients())==null).toList();
        var basis=!genuine.isEmpty()?genuine:planned.isEmpty()?matches:planned;
        Outcome outcome=matches.stream().anyMatch(ProductMatch::eligible)?Outcome.MATCHED:basis.isEmpty()?Outcome.UNMATCHED:basis.stream().allMatch(m->m.outcome()==basis.get(0).outcome())?basis.get(0).outcome():Outcome.INCOMPLETE;
        return new RequirementResult(r.requirementId(),r.menuLineId(),r.ingredientId(),r.quantity(),outcome,messages,matches,clock.instant(),truncated,checkedSubstitutes);
    }
    public ProcurementInputPackage pack(MarketResult r){return new ProcurementInputPackage(1,r.resultId,r.revision,r.planId,r.planVersion,r.assessmentId,r.profileVersion,r.planning.weeklyMealPlan(),r.planning.substitutionGroups(),r.planning,r.requirements,r.readyForProcurement,r.multiStoreComparable,r.completedAt);}
}
