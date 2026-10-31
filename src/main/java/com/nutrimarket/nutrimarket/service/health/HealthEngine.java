package com.nutrimarket.nutrimarket.service.health;

import com.nutrimarket.nutrimarket.config.HealthPolicy;
import com.nutrimarket.nutrimarket.service.planning.IngredientCatalog;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.nutrimarket.nutrimarket.model.dietary.DietaryModels.*;
import static com.nutrimarket.nutrimarket.model.health.HealthModels.*;

/** Target comparison, not medical diagnosis. Missing observations never become zero intake. */
@Component
public class HealthEngine {
    public static final Map<String,String> UNITS=Map.of("1008","kcal","1003","g","1004","g","1005","g","1079","g","1093","mg","1087","mg","1089","mg");
    public static final Map<String,String> NAMES=Map.of("1008","Energy","1003","Protein","1004","Fat","1005","Carbohydrate","1079","Dietary fibre","1093","Sodium","1087","Calcium","1089","Iron");
    private final HealthPolicy policy;private final IngredientCatalog catalog;
    public HealthEngine(HealthPolicy policy,IngredientCatalog catalog) { this.policy=policy;this.catalog=catalog; }
    /** Conditions outside the routine list, including unrecognised text, need an expert; routine ones are planning context only. */
    public List<String> conditionsRequiringReview(List<String> conditions) {
        Set<String> routine=new HashSet<>();policy.routineConditions().forEach(c->routine.add(normal(c)));
        return conditions.stream().filter(c->!routine.contains(normal(c))).toList();
    }
    private static String normal(String s) {return s.toLowerCase(Locale.ROOT).strip().replaceAll("\\s+"," ").replaceAll("[.;]+$","");}
    public IntakeCoverage coverage(Profile profile,LocalDate start,List<ValidatedDietaryIntakeRecord> records) {
        var days=byDay(profile,records);var incomplete=new ArrayList<LocalDate>();int missing=0,covered=0;
        for(int i=0;i<7;i++) {
            LocalDate day=start.plusDays(i);int count=days.getOrDefault(day,List.of()).size();
            if(count<policy.mealsPerDay()) {incomplete.add(day);missing+=policy.mealsPerDay()-count;} else covered++;
        }
        return new IntakeCoverage(start,start.plusDays(6),profile.information().timezone(),records.size(),days.size(),covered,
                policy.mealsPerDay(),missing,List.copyOf(incomplete));
    }
    private Map<LocalDate,List<ValidatedDietaryIntakeRecord>> byDay(Profile profile,List<ValidatedDietaryIntakeRecord> records) {
        Map<LocalDate,List<ValidatedDietaryIntakeRecord>> days=new TreeMap<>();var zone=ZoneId.of(profile.information().timezone());
        for(var record:records) days.computeIfAbsent(record.mealTime().atZone(zone).toLocalDate(),d->new ArrayList<>()).add(record);
        return days;
    }
    public Assessment evaluate(Profile profile,LocalDate start,List<ValidatedDietaryIntakeRecord> records,Instant now) {
        return evaluate(profile,start,records,now,false);
    }
    public Assessment evaluate(Profile profile,LocalDate start,List<ValidatedDietaryIntakeRecord> records,Instant now,boolean allowIncomplete) {
        var a=new Assessment(); a.assessmentId=UUID.randomUUID().toString();a.memberId=profile.memberId();a.profileVersion=profile.version();
        a.timezone=profile.information().timezone();a.policyVersion=policy.policyVersion();a.periodStart=start;a.periodEnd=start.plusDays(6);
        a.createdAt=now;a.updatedAt=now;a.requiredDays=7;a.requiredMealsPerDay=policy.mealsPerDay();
        a.intakeCount=records.size();a.recordIds=records.stream().map(r->r.recordId().toString()).toList();
        var info=profile.information();
        a.healthConstraintSet=new Constraints(info.allergies(),info.restrictions(),info.conditions(),info.targets(),List.of());
        a.targetSource=info.targetSource();a.targetRationale=info.targetRationale();
        var days=byDay(profile,records);var coverage=coverage(profile,start,records);
        a.coveredDays=coverage.coveredDays();a.observedDays=coverage.observedDays();a.missingMeals=coverage.missingMeals();
        a.incompleteRecords=coverage.requiresConfirmation();a.partialDataAccepted=a.incompleteRecords && allowIncomplete && !records.isEmpty();
        boolean enough=!records.isEmpty() && (!a.incompleteRecords || a.partialDataAccepted);
        if(records.isEmpty()) a.reasons.add("No validated meals were recorded for this period. Add at least one meal before assessing recorded intake.");
        else if(a.incompleteRecords) a.reasons.add(a.partialDataAccepted
                ?"Limited-data assessment: you chose to continue using "+a.intakeCount+" recorded meals across "+a.observedDays+" of 7 days. Unrecorded meals are unknown, not zero intake."
                :"Some meals may be missing. Add records or choose to assess the existing records; a full set of 21 meals is not required.");
        boolean missing=false,high=false,deviation=false;
        for(var entry:new TreeMap<>(info.targets()).entrySet()) {
            String id=entry.getKey(); Target target=entry.getValue();
            BigDecimal total=BigDecimal.ZERO;int observed=0,below=0,above=0,extreme=0;
            for(var day:days.values()) {
                BigDecimal daily=BigDecimal.ZERO;int count=0;
                for(var record:day) {
                    Nutrient n=record.nutrients().get(id);
                    if(n!=null && target.unit().equalsIgnoreCase(n.unit()) && n.amount()!=null && n.amount().signum()>=0) {
                        total=total.add(n.amount());daily=daily.add(n.amount());observed++;count++;
                    }
                }
                if(day.size()>=policy.mealsPerDay() && count==day.size()) {
                    if(daily.compareTo(target.minimum())<0) below++;
                    if(daily.compareTo(target.maximum())>0) above++;
                    if(daily.compareTo(target.minimum().multiply(policy.reviewLowRatio()))<0 || daily.compareTo(target.maximum().multiply(policy.reviewHighRatio()))>0) extreme++;
                }
            }
            boolean complete=!records.isEmpty() && observed==records.size();
            BigDecimal average=complete?total.divide(BigDecimal.valueOf(a.observedDays),2,RoundingMode.HALF_UP):null;
            String finding="NOT_EVALUATED";
            if(!complete) {missing=true;finding="MISSING_DATA";if(!records.isEmpty())a.reasons.add(NAMES.get(id)+" data is missing or has incompatible units; it has not been treated as zero intake.");}
            else if(enough) {
                finding=average.compareTo(target.minimum())<0?"BELOW_TARGET":average.compareTo(target.maximum())>0?"ABOVE_TARGET":"WITHIN_TARGET";
                if(!finding.equals("WITHIN_TARGET")) deviation=true;
                if(a.incompleteRecords) finding="RECORDED_"+finding;
                if(below>=policy.persistentDays()) a.reasons.add(NAMES.get(id)+" was below the personal target minimum on "+below+" covered days.");
                if(above>=policy.persistentDays()) a.reasons.add(NAMES.get(id)+" was above the personal target maximum on "+above+" covered days.");
                if(id.equals("1008") && extreme>=policy.persistentDays()) {high=true;a.reasons.add("Recorded energy deviates beyond the project review threshold on multiple days. Expert review is required.");}
            }
            a.comparisons.add(new Comparison(id,NAMES.get(id),target.unit(),observed>0?total:null,average,target.minimum(),target.maximum(),finding,below,above,observed));
        }
        if(records.stream().anyMatch(r->r.profileVersion()>profile.version())) {missing=true;a.reasons.add("Some meals reference a newer health profile version than the current profile. Check the profile version.");}
        if(!enough || missing) {
            a.status=AssessmentStatus.INSUFFICIENT_INTAKE_DATA;a.planningClearance=Clearance.NOT_READY;a.riskStatus=Risk.NOT_ASSESSED;
        } else {
            var review=conditionsRequiringReview(info.conditions());
            if(!review.isEmpty()) {high=true;a.reasons.add("The profile lists conditions outside the routine list ("+String.join(", ",review)+"); an expert must confirm suitable targets and planning constraints.");}
            // Meal plans have no expert review, so constraints planning cannot enforce are resolved here.
            var uninterpreted=catalog.unrecognised(info.allergies(),info.restrictions());
            if(!uninterpreted.isEmpty()) {high=true;a.reasons.add("Meal planning cannot interpret these allergies or dietary restrictions ("+String.join(", ",uninterpreted)+"); an expert must confirm the planning constraints.");}
            a.status=AssessmentStatus.COMPLETED;
            a.riskStatus=high?Risk.POTENTIAL_HIGH_RISK:a.incompleteRecords?Risk.LIMITED_DATA:deviation?Risk.TARGET_DEVIATION:Risk.WITHIN_TARGETS;
            a.planningClearance=high?Clearance.BLOCKED_FOR_EXPERT_REVIEW:Clearance.CLEARED;
            if(a.reasons.isEmpty()) a.reasons.add("Recorded intake for this period is within the supplied personal target ranges.");
            var routine=info.conditions().stream().filter(c->!review.contains(c)).toList();
            if(!routine.isEmpty()) a.reasons.add("Routine condition(s) "+String.join(", ",routine)+" are passed to planning as context; no expert review is required.");
        }
        return a;
    }
}
