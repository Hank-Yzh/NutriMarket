package com.nutrimarket.nutrimarket.model.planning;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.nutrimarket.nutrimarket.model.dietary.DietaryModels.*;
import com.nutrimarket.nutrimarket.model.health.HealthModels.*;

public final class PlanningModels {
    private PlanningModels() {}
    // REVIEW_REQUIRED and TERMINATED only occur in plans saved before plan-level expert review was removed; refresh() migrates REVIEW_REQUIRED.
    public enum State { GENERATING, DEFERRED, DRAFT, REVIEW_REQUIRED, PUBLISHED, STALE, TERMINATED }
    public enum Slot { BREAKFAST, LUNCH, DINNER }
    public enum ReplanReason { AVAILABILITY, PRICE, PACKAGE_SIZE, ELIGIBILITY, PREFERENCE }
    public record Generate(@NotNull UUID assessmentId,@NotNull LocalDate weekStart,@AssertTrue boolean aiConsent) {}
    public record Version(@Min(1) int expectedVersion) {}
    public record Publish(@Min(1) int expectedVersion,@AssertTrue boolean confirmSources) {}
    public record Portion(@NotBlank String ingredientId,@NotNull @DecimalMin("1") @DecimalMax("1000") BigDecimal grams) {}
    // dish: optional display name from the model; it never adds ingredients beyond the listed portions.
    public record CandidateMeal(@Min(0) @Max(6) int day,@NotNull Slot slot,
                                @NotEmpty @Size(max=8) List<@Valid Portion> ingredients,@Size(max=80) String dish) {
        public CandidateMeal(int day,Slot slot,List<Portion> ingredients){this(day,slot,ingredients,null);}
    }
    public record Candidate(@NotEmpty @Size(max=21) List<@Valid CandidateMeal> meals) {}
    public record Replan(@NotNull UUID requestId,@Min(1) int expectedVersion,@NotNull ReplanReason reason,
        @NotBlank @Size(max=2000) String evidence,@NotEmpty @Size(max=21) List<@NotBlank String> menuLineIds,
        @NotEmpty @Size(max=20) List<@NotBlank String> unavailableIngredientIds,@AssertTrue boolean aiConsent,
        UUID planId,@Min(1) Integer attempt) {
        // planId and attempt complete the 1.1.6 ReplanRequest contract; Agent 3 verifies them when supplied.
        public Replan(UUID requestId,int expectedVersion,ReplanReason reason,String evidence,List<String> menuLineIds,List<String> unavailableIngredientIds,boolean aiConsent) {
            this(requestId,expectedVersion,reason,evidence,menuLineIds,unavailableIngredientIds,aiConsent,null,null);
        }
    }
    public record Ingredient(String id,String name,String searchTerm,Long fdcId,Set<String> allergens,boolean vegan,boolean vegetarian) {}
    // A food the member does not want in meal plans (a preference, not a health constraint).
    public record FoodDislike(String ingredientId,String name) {}
    public record IngredientSource(Ingredient ingredient,NutritionMatch nutrition) {}
    public record Meal(String menuLineId,LocalDate date,Slot slot,String title,List<Portion> ingredients,Map<String,Nutrient> nutrients) {}
    public record DailyNutrition(LocalDate date,Map<String,Nutrient> nutrients) {}
    public record Requirement(String requirementId,String menuLineId,String ingredientId,String description,
                              BigDecimal quantity,String unit,String substitutionGroupId) {}
    public record Tolerance(BigDecimal minimum,BigDecimal maximum,String unit) {}
    // A planner-approved replacement for one meal line: its own edible quantity and tolerances keep that day within every target.
    public record Substitute(String ingredientId,BigDecimal quantity,Map<String,Tolerance> nutrientTolerances) {}
    public record SubstitutionGroup(String substitutionGroupId,String menuLineId,List<String> allowedIngredientIds,
        BigDecimal quantity,String unit,Map<String,Tolerance> nutrientTolerances,List<String> prohibitedSubstitutions,List<Substitute> substitutes) {
        public SubstitutionGroup(String substitutionGroupId,String menuLineId,List<String> allowedIngredientIds,BigDecimal quantity,String unit,Map<String,Tolerance> nutrientTolerances,List<String> prohibitedSubstitutions) {
            this(substitutionGroupId,menuLineId,allowedIngredientIds,quantity,unit,nutrientTolerances,prohibitedSubstitutions,null);
        }
        public List<Substitute> substituteList() {return substitutes==null?List.of():substitutes;}
        public Substitute substitute(String ingredientId) {return substituteList().stream().filter(s->s.ingredientId().equals(ingredientId)).findFirst().orElse(null);}
    }
    public record Event(Instant at,int version,String action,String detail) {}
    public record ReplanAudit(UUID requestId,int fromVersion,ReplanReason reason,String evidence,List<String> menuLineIds,
                              List<String> unavailableIngredientIds,Instant at,Integer attempt) {}  // null on audits recorded before attempt counts
    // Older documents may still carry reviewReasons and expertDecisions.
    @JsonIgnoreProperties(ignoreUnknown=true)
    public static class Plan {
        public int schemaVersion=1,version=1,attempts,replanCount,assessmentRevision;
        public String planId,memberId,assessmentId,policyVersion,model,generationToken,deliveryState;
        public long profileVersion;
        public LocalDate weekStart,weekEnd;
        public Instant createdAt,updatedAt,publishedAt;
        public State state;
        public Constraints constraints;
        public Candidate candidate;
        public List<Meal> meals=new ArrayList<>();
        public List<DailyNutrition> dailyNutrition=new ArrayList<>();
        public Map<String,IngredientSource> sources=new TreeMap<>();
        public Map<String,String> purchaseIngredients=new TreeMap<>();
        public List<String> issues=new ArrayList<>(),sourceNotes=new ArrayList<>(),priorities=new ArrayList<>(),excludedIngredients=new ArrayList<>(),affectedLines=new ArrayList<>();
        public List<Event> audit=new ArrayList<>();
        public List<ReplanAudit> replans=new ArrayList<>();
        public boolean stale;
    }
    public record PlanPage(List<Plan> items,int page,int size) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record PlanningPackage(int schemaVersion,String planId,int planVersion,String memberId,String assessmentId,
        long profileVersion,int assessmentRevision,String policyVersion,LocalDate weekStart,LocalDate weekEnd,
        List<Meal> weeklyMealPlan,List<Requirement> ingredientRequirements,List<SubstitutionGroup> substitutionGroups,
        Map<String,Target> nutritionTargets,Constraints healthConstraintSet,List<DailyNutrition> dailyNutrition,
        Map<String,IngredientSource> nutritionSources,Instant publishedAt) {}
}
