package com.nutrimarket.nutrimarket.service.planning;

import com.nutrimarket.nutrimarket.config.PlanningPolicy;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.*;
import com.nutrimarket.nutrimarket.model.dietary.DietaryModels.Nutrient;
import com.nutrimarket.nutrimarket.model.health.HealthModels.Target;
import com.nutrimarket.nutrimarket.service.health.HealthEngine;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;

/** Authoritative validation: model-provided nutrient totals and safety claims are never accepted. */
@Service
public class PlanningEngine {
    private static final double MIN_SCALE=0.25,MAX_SCALE=4,PENALTY=1e-3;
    private final IngredientCatalog catalog;private final PlanningPolicy policy;
    public PlanningEngine(IngredientCatalog catalog,PlanningPolicy policy){this.catalog=catalog;this.policy=policy;}
    public static String line(CandidateMeal meal){return "d"+meal.day()+"-"+meal.slot();}
    public static List<String> mealLines(){
        return com.nutrimarket.nutrimarket.model.planning.PlanningRules.mealLines();
    }
    public Candidate merge(Candidate existing,Candidate proposed,List<String> affected) {
        if(affected.isEmpty())return proposed;
        if(existing==null||existing.meals()==null||proposed==null||proposed.meals()==null)return null;
        Map<String,CandidateMeal> replacements=new HashMap<>(),kept=new HashMap<>();
        for(var meal:proposed.meals())if(meal!=null&&affected.contains(line(meal))&&replacements.put(line(meal),meal)!=null)return null;
        if(!replacements.keySet().equals(new HashSet<>(affected)))return null;
        for(var meal:existing.meals())if(meal!=null&&!affected.contains(line(meal))&&mealLines().contains(line(meal))){
            if(kept.put(line(meal),meal)!=null)return null;
        }
        List<CandidateMeal> merged=new ArrayList<>();
        for(String line:mealLines()){
            var meal=affected.contains(line)?replacements.get(line):kept.get(line);
            if(meal==null)return null;merged.add(meal);
        }
        return new Candidate(merged);
    }
    /** Describe structural errors before computing totals: a skipped meal is not evidence of nutrient deficiency. */
    public List<String> structureIssues(Candidate candidate){
        List<String> issues=new ArrayList<>();Map<String,Integer> counts=new HashMap<>();
        if(candidate==null||candidate.meals()==null)return List.of("Exactly 21 meals are required, covering seven days and three meal slots per day.");
        int index=0;
        for(var meal:candidate.meals()){
            index++;
            if(meal==null||meal.day()<0||meal.day()>6||meal.slot()==null){issues.add("Meal entry "+index+": invalid day or slot; use days 0–6 and BREAKFAST, LUNCH or DINNER.");continue;}
            String id=line(meal);counts.merge(id,1,Integer::sum);
            if(meal.ingredients()==null||meal.ingredients().isEmpty())issues.add(id+": missing ingredients; supply 1–8 distinct ingredients.");
            else if(meal.ingredients().size()>8)issues.add(id+": "+meal.ingredients().size()+" ingredients; maximum is 8. Remove extra ingredients and keep the dish name consistent.");
        }
        for(String id:mealLines()){
            int count=counts.getOrDefault(id,0);
            if(count==0)issues.add(id+": missing meal; supply exactly one meal for this slot.");
            else if(count>1)issues.add(id+": duplicate meal ("+count+" entries); supply exactly one meal for this slot.");
        }
        return List.copyOf(issues);
    }
    /** Repair only failed slots, or all three slots of a day with nutrient errors. Replans never widen their original scope. */
    public List<String> repairLines(Plan p){
        if(p.candidate==null||p.issues.isEmpty())return p.affectedLines;
        Set<String> failed=new LinkedHashSet<>();boolean global=false;
        for(String issue:p.issues){
            var line=java.util.regex.Pattern.compile("^(d[0-6]-(?:BREAKFAST|LUNCH|DINNER)):").matcher(issue);
            var day=java.util.regex.Pattern.compile("^Day ([1-7]):").matcher(issue);
            if(line.find())failed.add(line.group(1));
            else if(day.find()){int d=Integer.parseInt(day.group(1))-1;for(Slot slot:Slot.values())failed.add("d"+d+"-"+slot);}
            else if(!issue.startsWith("The model repeated"))global=true;
        }
        List<String> allowed=p.affectedLines.isEmpty()?mealLines():p.affectedLines;
        if(global||failed.isEmpty())return allowed;
        var selected=allowed.stream().filter(failed::contains).toList();
        return selected.isEmpty()?allowed:selected;
    }
    public boolean preservesUnaffected(Candidate existing,Candidate proposed,List<String> affected) {
        if(existing==null||proposed==null||proposed.meals()==null)return false;
        var before=existing.meals().stream().filter(m->!affected.contains(line(m))).toList();
        var after=proposed.meals().stream().filter(Objects::nonNull).filter(m->!affected.contains(line(m))).toList();
        return before.size()==after.size() && new HashSet<>(before).equals(new HashSet<>(after));
    }
    /** Rescales proposed portions toward daily target midpoints. Ingredients, meals and unaffected replan lines stay as proposed; validate() remains authoritative. */
    public Candidate tune(Plan p) {
        var c=p.candidate;if(!structureIssues(c).isEmpty())return null;
        for(var meal:c.meals())if(meal==null||meal.slot()==null||meal.ingredients()==null||meal.ingredients().stream().anyMatch(i->i==null||i.grams()==null||i.grams().signum()<=0||catalog.get(i.ingredientId())==null||p.sources.get(i.ingredientId())==null))return null;
        var keys=new ArrayList<>(p.constraints.dailyTargets().keySet());int n=keys.size();double[] mid=new double[n],half=new double[n];
        // Missing nutrient data is never treated as zero, so such plans are left for validation feedback.
        for(var meal:c.meals())for(var portion:meal.ingredients())if(perGram(p,portion.ingredientId(),keys)==null)return null;
        for(int k=0;k<n;k++){var t=p.constraints.dailyTargets().get(keys.get(k));mid[k]=(t.minimum().doubleValue()+t.maximum().doubleValue())/2;half[k]=Math.max((t.maximum().doubleValue()-t.minimum().doubleValue())/2,mid[k]*0.01);}
        Set<String> repairable=new HashSet<>(repairLines(p));
        var meals=c.meals();double[][] grams=new double[meals.size()][];
        for(int m=0;m<meals.size();m++)grams[m]=meals.get(m).ingredients().stream().mapToDouble(i->i.grams().doubleValue()).toArray();
        for(int day=0;day<7;day++) {
            List<int[]> free=new ArrayList<>();List<double[]> coefficients=new ArrayList<>();double[] total=new double[n];
            for(int m=0;m<meals.size();m++){var meal=meals.get(m);if(meal.day()!=day)continue;boolean tunable=(p.affectedLines.isEmpty()||p.affectedLines.contains(line(meal)))&&(p.issues.isEmpty()||repairable.contains(line(meal)));
                for(int i=0;i<grams[m].length;i++){var a=perGram(p,meal.ingredients().get(i).ingredientId(),keys);for(int k=0;k<n;k++)total[k]+=a[k]*grams[m][i];if(tunable){free.add(new int[]{m,i});coefficients.add(a);}}}
            // Coordinate descent on bounded least squares; the small penalty keeps portions close to the proposal.
            for(int sweep=0;sweep<500;sweep++) {
                double moved=0;
                for(int v=0;v<free.size();v++) {
                    int m=free.get(v)[0],i=free.get(v)[1];double[] a=coefficients.get(v);var portion=meals.get(m).ingredients().get(i);
                    double x0=portion.grams().doubleValue(),x=grams[m][i],num=PENALTY/x0,den=PENALTY/(x0*x0),others=Arrays.stream(grams[m]).sum()-x;
                    for(int k=0;k<n;k++){num+=a[k]*(mid[k]-(total[k]-a[k]*x))/(half[k]*half[k]);den+=a[k]*a[k]/(half[k]*half[k]);}
                    double hi=Math.min(Math.min(catalog.maxGrams(portion.ingredientId()),MAX_SCALE*x0),1490-others);
                    double next=Math.max(Math.max(1,MIN_SCALE*x0),Math.min(hi,num/den));
                    for(int k=0;k<n;k++)total[k]+=a[k]*(next-x);grams[m][i]=next;moved=Math.max(moved,Math.abs(next-x));
                }
                if(moved<1e-6)break;
            }
        }
        boolean changed=false;List<CandidateMeal> tuned=new ArrayList<>();
        for(int m=0;m<meals.size();m++){var meal=meals.get(m);List<Portion> portions=new ArrayList<>();
            for(int i=0;i<grams[m].length;i++){var portion=meal.ingredients().get(i);
                if(Math.abs(grams[m][i]-portion.grams().doubleValue())<0.5)portions.add(portion);
                else{portions.add(new Portion(portion.ingredientId(),BigDecimal.valueOf(Math.max(1,Math.round(grams[m][i])))));changed=true;}}
            tuned.add(new CandidateMeal(meal.day(),meal.slot(),portions,meal.dish()));}
        return changed?new Candidate(tuned):null;
    }
    private double[] perGram(Plan p,String id,List<String> keys) {
        double[] a=new double[keys.size()];var values=p.sources.get(id).nutrition().per100g();
        for(int k=0;k<a.length;k++){var n=values.get(keys.get(k));if(n==null||n.amount()==null||n.amount().signum()<0||!p.constraints.dailyTargets().get(keys.get(k)).unit().equalsIgnoreCase(n.unit()))return null;a[k]=n.amount().doubleValue()/100;}
        return a;
    }
    public void validate(Plan p) {
        p.issues=new ArrayList<>();p.sourceNotes=new ArrayList<>();p.meals=new ArrayList<>();p.dailyNutrition=new ArrayList<>();
        p.issues.addAll(structureIssues(p.candidate));if(!p.issues.isEmpty())return;
        Set<String> noted=new HashSet<>();
        Map<Integer,Map<String,BigDecimal>> days=new TreeMap<>();
        for(var meal:p.candidate.meals()) {
            Map<String,BigDecimal> totals=new TreeMap<>();Set<String> ids=new HashSet<>();BigDecimal weight=BigDecimal.ZERO;
            for(var portion:meal.ingredients()) {
                if(portion==null){p.issues.add(line(meal)+": missing ingredient.");continue;}
                var ingredient=catalog.get(portion.ingredientId());var grams=portion.grams();
                int limit=catalog.maxGrams(portion.ingredientId());
                // Specific reasons are fed back to the model so the next attempt can correct them.
                String problem=ingredient==null?"unknown ingredient ID "+String.valueOf(portion.ingredientId()).substring(0,Math.min(40,String.valueOf(portion.ingredientId()).length()))+"; use only catalog IDs"
                    :grams==null || grams.compareTo(BigDecimal.ONE)<0 || grams.compareTo(BigDecimal.valueOf(limit))>0?ingredient.id()+" must be 1–"+limit+" g"
                    :!ids.add(portion.ingredientId())?"duplicate ingredient "+ingredient.id():null;
                if(problem!=null){p.issues.add(line(meal)+": invalid ingredient or portion ("+problem+").");continue;}
                weight=weight.add(grams);
                if(!catalog.allowed(ingredient,p.constraints)||p.replans.stream().anyMatch(r->r.menuLineIds().contains(line(meal))&&r.unavailableIngredientIds().contains(ingredient.id())))p.issues.add(line(meal)+": prohibited ingredient "+ingredient.name()+".");
                var source=p.sources.get(ingredient.id());
                if(source==null){p.issues.add(line(meal)+": missing verified nutrition source for "+ingredient.name()+".");continue;}
                if(source.nutrition().matchConfidence().compareTo(new BigDecimal("0.95"))<0 && noted.add(ingredient.id()))p.sourceNotes.add("Approximate USDA match for "+ingredient.name()+": "+source.nutrition().description());
                for(var target:p.constraints.dailyTargets().entrySet()) {
                    var n=source.nutrition().per100g().get(target.getKey());
                    if(n==null||n.amount()==null||n.amount().signum()<0||!target.getValue().unit().equalsIgnoreCase(n.unit())){p.issues.add(line(meal)+": missing or incompatible "+HealthEngine.NAMES.get(target.getKey())+" data for "+ingredient.name()+".");continue;}
                    totals.merge(target.getKey(),n.amount().multiply(grams).divide(BigDecimal.valueOf(100)),BigDecimal::add);
                }
            }
            if(weight.compareTo(BigDecimal.valueOf(1500))>0)p.issues.add(line(meal)+": meal exceeds the 1500 g limit.");
            var day=days.computeIfAbsent(meal.day(),k->new TreeMap<>());totals.forEach((id,value)->day.merge(id,value,BigDecimal::add));
            String title=meal.dish()!=null&&!meal.dish().isBlank()&&meal.dish().length()<=80?meal.dish().strip()
                :meal.ingredients().stream().filter(Objects::nonNull).map(i->catalog.get(i.ingredientId())).filter(Objects::nonNull).map(Ingredient::name).reduce((a,b)->a+", "+b).orElse("Meal");
            p.meals.add(new Meal(line(meal),p.weekStart.plusDays(meal.day()),meal.slot(),title,meal.ingredients(),nutrients(p,totals)));
        }
        for(int day=0;day<7;day++) {
            var totals=days.getOrDefault(day,Map.of());
            for(var entry:p.constraints.dailyTargets().entrySet()) {
                var amount=totals.get(entry.getKey());var target=entry.getValue();
                if(amount==null || amount.compareTo(target.minimum())<0 || amount.compareTo(target.maximum())>0)p.issues.add("Day "+(day+1)+": "+HealthEngine.NAMES.get(entry.getKey())+" must be within "+target.minimum()+"–"+target.maximum()+" "+target.unit()+"; calculated "+(amount==null?"unknown":amount.stripTrailingZeros().toPlainString())+".");
            }
            p.dailyNutrition.add(new DailyNutrition(p.weekStart.plusDays(day),nutrients(p,totals)));
        }
        p.meals.sort(Comparator.comparing(Meal::date).thenComparing(Meal::slot));
        p.issues=p.issues.stream().distinct().limit(100).toList();
    }
    private Map<String,Nutrient> nutrients(Plan p,Map<String,BigDecimal> amounts) {
        Map<String,Nutrient> result=new TreeMap<>();amounts.forEach((id,value)->result.put(id,new Nutrient(value.setScale(2,RoundingMode.HALF_UP),p.constraints.dailyTargets().get(id).unit())));return result;
    }
    public PlanningPackage pack(Plan p) {
        List<Requirement> requirements=new ArrayList<>();List<SubstitutionGroup> groups=new ArrayList<>();var targets=p.constraints.dailyTargets();
        Map<LocalDate,Map<String,BigDecimal>> days=new HashMap<>();
        for(var meal:p.meals)for(var part:meal.ingredients())targets.keySet().forEach(k->days.computeIfAbsent(meal.date(),d->new HashMap<>()).merge(k,contribution(p,part,k),BigDecimal::add));
        for(var meal:p.meals)for(var part:meal.ingredients()) {
            String id=meal.menuLineId()+"-"+part.ingredientId(),group="sg-"+id;
            requirements.add(new Requirement(id,meal.menuLineId(),part.ingredientId(),catalog.purchaseName(part.ingredientId()),part.grams(),"g edible ready-to-eat weight",group));
            Map<String,Tolerance> tolerances=new TreeMap<>();
            targets.forEach((nutrient,target)->tolerances.put(nutrient,tolerance(contribution(p,part,nutrient),days.get(meal.date()).get(nutrient),target)));
            List<String> prohibited=new ArrayList<>(List.of("Any ingredient outside allowedIngredientIds","Unverified SKU allergens or cross-contact","Any change violating daily targets or hard constraints"));
            prohibited.addAll(p.constraints.excludedAllergens());prohibited.addAll(p.constraints.dietaryRestrictions());prohibited.addAll(p.constraints.expertInstructions());
            var substitutes=substitutes(p,meal,part,days.get(meal.date()));var allowed=new ArrayList<>(List.of(part.ingredientId()));substitutes.forEach(s->allowed.add(s.ingredientId()));
            groups.add(new SubstitutionGroup(group,meal.menuLineId(),List.copyOf(allowed),part.grams(),"g edible ready-to-eat weight",tolerances,prohibited,substitutes));
        }
        Map<String,IngredientSource> used=new TreeMap<>();requirements.forEach(r->used.put(r.ingredientId(),p.sources.get(r.ingredientId())));
        return new PlanningPackage(1,p.planId,p.version,p.memberId,p.assessmentId,p.profileVersion,p.assessmentRevision,p.policyVersion,p.weekStart,p.weekEnd,p.meals,requirements,groups,p.constraints.dailyTargets(),p.constraints,p.dailyNutrition,used,p.publishedAt);
    }
    /**
     * Up to three replacements the market may buy if the planned food is unavailable. Each uses an energy-matched amount and is kept
     * only if swapping it in alone keeps the day within every target; its tolerances share that day's remaining margin.
     */
    private List<Substitute> substitutes(Plan p,Meal meal,Portion part,Map<String,BigDecimal> day) {
        List<Substitute> out=new ArrayList<>();var targets=p.constraints.dailyTargets();var energy=per100(p,part.ingredientId(),"1008");
        for(String alt:catalog.alternatives(part.ingredientId())) {
            if(out.size()==3)break;
            var ingredient=catalog.get(alt);var altEnergy=per100(p,alt,"1008");
            if(ingredient==null||energy==null||altEnergy==null||altEnergy.signum()<=0||!catalog.allowed(ingredient,p.constraints)||p.excludedIngredients.contains(alt)
                ||meal.ingredients().stream().anyMatch(i->alt.equals(i.ingredientId()))
                ||p.replans.stream().anyMatch(r->r.menuLineIds().contains(meal.menuLineId())&&r.unavailableIngredientIds().contains(alt)))continue;
            var grams=part.grams().multiply(energy).divide(altEnergy,0,RoundingMode.HALF_UP);
            if(grams.compareTo(BigDecimal.ONE)<0||grams.compareTo(BigDecimal.valueOf(catalog.maxGrams(alt)))>0)continue;
            Map<String,Tolerance> tolerances=new TreeMap<>();boolean fits=true;
            for(var t:targets.entrySet()) {
                var per=per100(p,alt,t.getKey());if(per==null){fits=false;break;}
                var amount=per.multiply(grams).divide(BigDecimal.valueOf(100));var swapped=day.get(t.getKey()).subtract(contribution(p,part,t.getKey())).add(amount);
                if(swapped.compareTo(t.getValue().minimum())<0||swapped.compareTo(t.getValue().maximum())>0){fits=false;break;}
                tolerances.put(t.getKey(),tolerance(amount,swapped,t.getValue()));
            }
            if(fits)out.add(new Substitute(alt,grams,tolerances));
        }
        return out;
    }
    private BigDecimal per100(Plan p,String ingredient,String nutrient) {
        var source=p.sources.get(ingredient);if(source==null)return null;var n=source.nutrition().per100g().get(nutrient);var target=p.constraints.dailyTargets().get(nutrient);
        return n==null||n.amount()==null||n.amount().signum()<0||target==null||!target.unit().equalsIgnoreCase(n.unit())?null:n.amount();
    }
    public Plan purchaseIngredients(Plan p) {
        p.purchaseIngredients=new TreeMap<>();
        for(var meal:p.meals)for(var part:meal.ingredients())p.purchaseIngredients.put(part.ingredientId(),catalog.purchaseName(part.ingredientId()));
        return p;
    }
    /** Adapt names only; published nutritional evidence, portions and tolerances stay immutable. */
    public PlanningPackage forPurchasing(PlanningPackage p) {
        if(p.ingredientRequirements()==null)return p;
        return new PlanningPackage(p.schemaVersion(),p.planId(),p.planVersion(),p.memberId(),p.assessmentId(),
            p.profileVersion(),p.assessmentRevision(),p.policyVersion(),p.weekStart(),p.weekEnd(),p.weeklyMealPlan(),
            p.ingredientRequirements().stream().map(catalog::purchaseRequirement).toList(),p.substitutionGroups(),
            p.nutritionTargets(),p.healthConstraintSet(),p.dailyNutrition(),p.nutritionSources(),p.publishedAt());
    }
    private BigDecimal contribution(Plan p,Portion part,String nutrient){return p.sources.get(part.ingredientId()).nutrition().per100g().get(nutrient).amount().multiply(part.grams()).divide(BigDecimal.valueOf(100));}
    /** Shares each day's remaining target range in proportion to contribution, so any combination of in-tolerance substitutes keeps the day within target. */
    private Tolerance tolerance(BigDecimal amount,BigDecimal day,Target target) {
        if(day.signum()<=0)return new Tolerance(amount,amount,target.unit());
        var cap=amount.multiply(BigDecimal.valueOf(policy.substitutionTolerance()));
        var down=day.subtract(target.minimum()).max(BigDecimal.ZERO).multiply(amount).divide(day,6,RoundingMode.FLOOR).min(cap);
        var up=target.maximum().subtract(day).max(BigDecimal.ZERO).multiply(amount).divide(day,6,RoundingMode.FLOOR).min(cap);
        return new Tolerance(amount.subtract(down),amount.add(up),target.unit());
    }
}
