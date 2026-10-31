package com.nutrimarket.nutrimarket.model.market;

import com.nutrimarket.nutrimarket.model.dietary.DietaryModels.Nutrient;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class MarketModels {
    private MarketModels() {}
    public enum Availability { IN_STOCK, OUT_OF_STOCK, UNKNOWN }
    public enum Compliance { COMPLIANT, NON_COMPLIANT, UNKNOWN }
    // Retailer range tier recorded with the catalogue evidence (e.g. a value, standard or premium range); null when not recorded.
    public enum QualityTier { BASIC, STANDARD, PREMIUM }
    public enum Outcome { MATCHED, UNMATCHED, UNAVAILABLE, LOW_CONFIDENCE, INCOMPLETE, STALE, CONSTRAINT_BLOCKED, SOURCE_FAILURE, MODEL_FAILURE }
    public enum State { MATCHING, COMPLETED, NEEDS_ATTENTION, STALE, INTERRUPTED }
    public record ProductInput(@Min(0) int expectedVersion,@NotBlank @Size(max=100) String sourceId,
        @NotBlank @Size(max=100) String sku,@NotBlank @Size(max=250) String name,@NotBlank @Size(max=100) String brand,
        @NotBlank @Size(max=100) String category,@NotBlank @Size(max=40) String ingredientId,
        @NotBlank @Size(max=40) String preparation,@DecimalMin("0.1") @DecimalMax("10") BigDecimal cookedYieldFactor,@NotBlank @Size(max=100) String store,
        @Pattern(regexp="[A-Z]{3}") @NotNull String currency,@DecimalMin("0") BigDecimal price,
        @DecimalMin("0.01") BigDecimal packageGrams,@Size(max=500) String promotion,@NotNull Availability availability,
        @NotNull Instant observedAt,@NotBlank @Size(max=1000) String sourceReference,
        @NotNull @Size(max=100) List<@NotBlank @Size(max=100) String> ingredients,
        @NotNull @Size(max=40) List<@NotBlank @Size(max=100) String> allergens,
        @NotNull @Size(max=40) List<@NotBlank @Size(max=100) String> mayContain,
        @NotNull @Size(max=40) List<@NotBlank @Size(max=100) String> dietaryClaims,
        boolean ingredientsComplete,boolean allergensComplete,@NotNull @Size(max=50) Map<String,@NotNull Nutrient> per100g,
        boolean enabled,@AssertTrue boolean evidenceConfirmed,QualityTier qualityTier) {
        public ProductInput(int expectedVersion,String sourceId,String sku,String name,String brand,String category,String ingredientId,
            String preparation,BigDecimal cookedYieldFactor,String store,String currency,BigDecimal price,BigDecimal packageGrams,String promotion,
            Availability availability,Instant observedAt,String sourceReference,List<String> ingredients,List<String> allergens,List<String> mayContain,
            List<String> dietaryClaims,boolean ingredientsComplete,boolean allergensComplete,Map<String,Nutrient> per100g,boolean enabled,boolean evidenceConfirmed) {
            this(expectedVersion,sourceId,sku,name,brand,category,ingredientId,preparation,cookedYieldFactor,store,currency,price,packageGrams,promotion,
                availability,observedAt,sourceReference,ingredients,allergens,mayContain,dietaryClaims,ingredientsComplete,allergensComplete,per100g,enabled,evidenceConfirmed,null);
        }
    }
    public record Product(String productId,int version,String updatedBy,Instant updatedAt,ProductInput facts) {}
    public record CatalogPage(List<Product> items,int page,int size) {}
    public record CatalogResult(List<Product> products,String source,boolean truncated) {}
    public record MatchScore(String productId,BigDecimal confidence) {}
    public record SemanticResponse(List<MatchScore> matches) {}
    public record Start(@NotNull UUID planId,@Min(1) int planVersion) {}
    public record Refresh(@NotNull UUID requestId,@Min(1) int expectedRevision,@NotEmpty @Size(max=21) List<@NotBlank String> menuLineIds,
        UUID planId,@Size(max=36) String basketId,Instant requiredFreshAfter) {
        // planId, basket context and required freshness complete the 1.1.6 market refresh contract.
        public Refresh(UUID requestId,int expectedRevision,List<String> menuLineIds) {this(requestId,expectedRevision,menuLineIds,null,null,null);}
    }
    public record ProductMatch(Product product,BigDecimal confidence,String matchingMethod,Compliance hardConstraintStatus,
        boolean eligible,Outcome outcome,List<String> reasons,BigDecimal unitPricePer100g,BigDecimal packagesToCover,BigDecimal costToCover) {}
    // checkedSubstitutes: planner substitutes whose catalogue products were checked for this line; null means not checked (results from before).
    public record RequirementResult(String requirementId,String menuLineId,String ingredientId,BigDecimal requiredGrams,
        Outcome outcome,List<String> messages,List<ProductMatch> candidates,Instant checkedAt,boolean truncated,List<String> checkedSubstitutes) {
        public RequirementResult(String requirementId,String menuLineId,String ingredientId,BigDecimal requiredGrams,Outcome outcome,List<String> messages,List<ProductMatch> candidates,Instant checkedAt,boolean truncated) {
            this(requirementId,menuLineId,ingredientId,requiredGrams,outcome,messages,candidates,checkedAt,truncated,null);
        }
    }
    public record Event(Instant at,String action,String detail) {}
    // Weekly package-based price comparison. Derived from the requirement results; Agent 5 still selects the final products.
    public record ComparisonOption(String productId,String sku,String name,String brand,String store,String currency,BigDecimal price,
        BigDecimal unitPricePerKg,BigDecimal packageGrams,BigDecimal requiredSoldGrams,BigDecimal packages,BigDecimal totalCost,BigDecimal surplusGrams,
        Compliance hardConstraintStatus,boolean eligible,Outcome outcome,List<String> reasons,List<String> dietaryClaims,Availability availability,
        String promotion,String sourceId,String sourceReference,Instant observedAt,
        // ingredientId differs from the comparison's ingredient when this is a planner substitute; confidence is the lowest across its meal lines.
        String ingredientId,BigDecimal confidence,String matchingMethod) {}
    public record IngredientComparison(String ingredientId,String name,BigDecimal requiredEdibleGrams,int menuLines,List<ComparisonOption> options) {}
    public record StoreTotal(String store,String currency,BigDecimal weeklyTotal,int ingredientsCovered,int ingredientsRequired,List<String> missingIngredients) {}
    public record PriceComparison(List<IngredientComparison> ingredients,List<StoreTotal> stores,Instant computedAt) {}
    public record RefreshAudit(UUID requestId,int fromRevision,List<String> menuLineIds,Instant at,String basketId,Instant requiredFreshAfter) {}
    public static class MarketResult {
        public int schemaVersion=1,revision=1,planVersion,refreshCount,preOrderRefreshCount;
        public String resultId,memberId,planId,assessmentId,policyVersion,generationToken,deliveryState;
        public long profileVersion;
        public State state;
        public Instant createdAt,updatedAt,completedAt,requiredFreshAfter;
        public PlanningPackage planning;
        public List<RequirementResult> requirements=new ArrayList<>();
        public List<Event> audit=new ArrayList<>();
        public List<RefreshAudit> refreshes=new ArrayList<>();
        public List<String> affectedLines=new ArrayList<>();
        public boolean readyForProcurement,multiStoreComparable,planStale;
        public PriceComparison priceComparison;
    }
    public record ResultPage(List<MarketResult> items,int page,int size) {}
    public record ProcurementInputPackage(int schemaVersion,String resultId,int marketRevision,String planId,int planVersion,
        String assessmentId,long profileVersion,List<Meal> weeklyMealPlan,List<SubstitutionGroup> substitutionGroups,
        PlanningPackage originalPlanningPackage,List<RequirementResult> marketIntelligenceResult,
        boolean readyForProcurement,boolean multiStoreComparable,Instant generatedAt) {}
}
