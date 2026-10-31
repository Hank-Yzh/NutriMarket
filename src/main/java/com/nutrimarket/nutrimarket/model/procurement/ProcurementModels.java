package com.nutrimarket.nutrimarket.model.procurement;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.nutrimarket.nutrimarket.model.market.MarketModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class ProcurementModels {
    private ProcurementModels() {}
    public enum State { DRAFT, BLOCKED, CHECKING, CHECKOUT_READY, STALE, ORDERED, REVIEW_REQUIRED, TERMINATED }
    // Price-quality preference (PM-07). Baskets saved before it existed used LOWEST_COST / FEWER_STORES and are read as ECONOMY.
    public enum Preference { ECONOMY, BALANCED, PREMIUM;
        @JsonCreator public static Preference of(String value) {return switch(value){case "LOWEST_COST","FEWER_STORES"->ECONOMY;default->valueOf(value);};}
    }
    public enum Fulfilment { PICKUP, DELIVERY }
    // DISLIKE swaps the product and is remembered as a soft exclusion in later baskets; DISLIKE_FOOD asks Agent 3 to replace the meals with that food.
    public enum ItemChoice { DISLIKE, NOT_NEEDED, DISLIKE_FOOD }
    // Ingredient scope is resolved by the server from the selected basket line, never trusted from the browser.
    // For DISLIKE_FOOD it is the product's own food, which may differ from the planned ingredient it substitutes.
    public record ItemEdit(@NotNull UUID productId,@NotNull ItemChoice choice,List<String> ingredientIds) {
        public ItemEdit(UUID productId,ItemChoice choice){this(productId,choice,null);}
    }
    public enum ExclusionType { BRAND, SKU }
    // learned marks an exclusion carried over from an earlier basket (PM-09); it never blocks a meal on its own.
    public record Exclusion(@NotNull ExclusionType type,@NotBlank @Size(max=100) String value,Boolean learned) {
        public Exclusion(ExclusionType type,String value) {this(type,value,false);}
        public boolean wasLearned() {return Boolean.TRUE.equals(learned);}
    }
    public record Settings(@NotNull @DecimalMin("0") @DecimalMax("1000000") BigDecimal budget,
        @NotNull @Pattern(regexp="[A-Z]{3}") String currency,@NotNull Preference preference,
        @NotNull Fulfilment fulfilment,@NotNull @Size(max=20) List<@NotBlank @Size(max=100) String> preferredStores,
        @NotNull @Size(max=100) List<@NotNull UUID> excludedProductIds,Boolean autoReplan,
        @Size(max=50) List<@NotNull @Valid Exclusion> exclusions,
        @Size(max=100) List<@NotNull @Valid ItemEdit> itemEdits) {
        public Settings(BigDecimal budget,String currency,Preference preference,Fulfilment fulfilment,List<String> preferredStores,List<UUID> excludedProductIds,Boolean autoReplan,List<Exclusion> exclusions) {
            this(budget,currency,preference,fulfilment,preferredStores,excludedProductIds,autoReplan,exclusions,List.of());
        }
        public Settings(BigDecimal budget,String currency,Preference preference,Fulfilment fulfilment,List<String> preferredStores,List<UUID> excludedProductIds) {
            this(budget,currency,preference,fulfilment,preferredStores,excludedProductIds,false,List.of());
        }
        public Settings(BigDecimal budget,String currency,Preference preference,Fulfilment fulfilment,List<String> preferredStores,List<UUID> excludedProductIds,Boolean autoReplan) {
            this(budget,currency,preference,fulfilment,preferredStores,excludedProductIds,autoReplan,List.of());
        }
        // Standing consent for Agent 5 to issue ReplanRequests (sent to Qwen by Agent 3) without another click.
        public boolean automaticReplanning() {return Boolean.TRUE.equals(autoReplan);}
        public List<Exclusion> exclusionList() {return exclusions==null?List.of():exclusions;}
        public List<ItemEdit> itemEditList() {return itemEdits==null?List.of():itemEdits;}
        public Set<String> omittedIngredients() {return itemEditList().stream().filter(e->e.choice()==ItemChoice.NOT_NEEDED)
            .flatMap(e->Objects.requireNonNullElse(e.ingredientIds(),List.<String>of()).stream()).collect(java.util.stream.Collectors.toSet());}
        public Set<String> dislikedFoods() {return itemEditList().stream().filter(e->e.choice()==ItemChoice.DISLIKE_FOOD)
            .flatMap(e->Objects.requireNonNullElse(e.ingredientIds(),List.<String>of()).stream()).collect(java.util.stream.Collectors.toCollection(TreeSet::new));}
    }
    public record Start(@NotNull UUID resultId) {}
    // aiConsent is needed only when a newly disliked food appears in the meal plan, because Agent 3 then sends the replan to Qwen.
    public record Change(@Min(1) int expectedVersion,@NotNull @Valid Settings settings,Boolean aiConsent) {
        public Change(int expectedVersion,Settings settings) {this(expectedVersion,settings,null);}
    }
    public record OptionProduct(String productId,String name,String sku,String store,QualityTier qualityTier,
                                BigDecimal packages,BigDecimal packageGrams,BigDecimal price,BigDecimal subtotal) {}
    public record StoreQuote(String store,BigDecimal products,BigDecimal fee,boolean feeQuoted,BigDecimal total) {}
    public record BasketOption(Preference preference,Fulfilment fulfilment,boolean available,BigDecimal productTotal,
        BigDecimal feeTotal,BigDecimal total,List<StoreQuote> stores,List<OptionProduct> products,List<String> issues,boolean searchLimited) {}
    public record BasketComparison(int version,int marketRevision,String currency,BigDecimal balancedMargin,List<BasketOption> options) {}
    public record Version(@Min(1) int expectedVersion) {}
    // Used for both a refresh-only checkout and a submission (refresh, then order directly when nothing material changed).
    public record Checkout(@Min(1) int expectedVersion,@NotNull UUID requestId) {}
    // Re-confirmation of the refreshed basket, including every listed material change (PM-08).
    public record Confirm(@Min(1) int expectedVersion,@NotNull UUID requestId,@AssertTrue @JsonAlias("confirmLocalOrder") boolean confirmOrder) {}
    public record OrderAction(@NotNull UUID requestId) {}
    public record ReplanInput(@Min(1) int expectedVersion,@NotNull UUID requestId,@AssertTrue boolean aiConsent) {}
    public record Review(@Min(1) int expectedVersion,@NotNull Decision decision,@NotBlank @Size(max=2000) String rationale) {}
    public enum Decision { RETURN_FOR_ADJUSTMENT, TERMINATE }
    // plannedIngredientId differs from the product's ingredient when the line substitutes within the planner's group (PM-03).
    // pantryGrams: edible grams taken from the member's pantry; soldGrams is what still has to be bought.
    public record Allocation(String requirementId,String menuLineId,BigDecimal edibleGrams,BigDecimal soldGrams,ProductMatch evidence,
                             String plannedIngredientId,String substitutionGroupId,BigDecimal pantryGrams) {
        public Allocation(String requirementId,String menuLineId,BigDecimal edibleGrams,BigDecimal soldGrams,ProductMatch evidence,String plannedIngredientId,String substitutionGroupId) {
            this(requirementId,menuLineId,edibleGrams,soldGrams,evidence,plannedIngredientId,substitutionGroupId,null);
        }
        public BigDecimal fromPantry() {return pantryGrams==null?BigDecimal.ZERO:pantryGrams;}
    }
    // One traceable constraint check behind a selected product (PM-02).
    public record Check(String constraint,String result,String evidence) {}
    // pantryGrams: edible grams of this line's meals supplied from the pantry instead of being bought.
    public record Line(Product product,BigDecimal requiredSoldGrams,BigDecimal packages,BigDecimal purchasedGrams,
                       BigDecimal surplusGrams,BigDecimal subtotal,List<Allocation> allocations,List<Check> checks,BigDecimal pantryGrams) {
        public Line(Product product,BigDecimal requiredSoldGrams,BigDecimal packages,BigDecimal purchasedGrams,BigDecimal surplusGrams,BigDecimal subtotal,List<Allocation> allocations,List<Check> checks) {
            this(product,requiredSoldGrams,packages,purchasedGrams,surplusGrams,subtotal,allocations,checks,null);
        }
        public BigDecimal fromPantry() {return pantryGrams==null?BigDecimal.ZERO:pantryGrams;}
    }
    public record Event(Instant at,String action,String detail) {}
    // address: the store's street address when configured; null for baskets saved before store addresses existed.
    public record StoreFee(String store,Fulfilment fulfilment,BigDecimal amount,String currency,boolean quoted,String address) {
        public StoreFee(String store,Fulfilment fulfilment,BigDecimal amount,String currency,boolean quoted){this(store,fulfilment,amount,currency,quoted,null);}
    }
    // total = productTotal + quoted fees. promotionDecision is set only when a promotion broke a near-tie (PM-04);
    // qualityDecision only when Balanced or Premium bought higher recorded range tiers than the lowest-cost basket.
    public record Evaluation(List<Line> lines,BigDecimal total,List<String> issues,List<String> unfulfilledMenuLines,
                             boolean searchLimited,String explanation,BigDecimal productTotal,List<StoreFee> fees,String promotionDecision,String qualityDecision) {
        public Evaluation(List<Line> lines,BigDecimal total,List<String> issues,List<String> unfulfilledMenuLines,
                          boolean searchLimited,String explanation,BigDecimal productTotal,List<StoreFee> fees,String promotionDecision) {
            this(lines,total,issues,unfulfilledMenuLines,searchLimited,explanation,productTotal,fees,promotionDecision,null);
        }
    }
    // Differences between the basket the member reviewed and the refreshed one; any of them requires re-confirmation (PM-08).
    // productId is the product now selected, so the line can show its substitution marker.
    public enum ChangeType { PRICE, PACKAGE, SUBSTITUTION, STOCK, FEE, TOTAL }
    public record MaterialChange(ChangeType type,String subject,String before,String after,String detail,String productId) {}
    public static class Basket {
        public String basketId,memberId,resultId,planId,assessmentId,policyVersion,orderId;
        public int version=1,marketRevision,planVersion,checkoutCount;
        public long profileVersion;
        public State state;
        public Settings settings;
        public ProcurementInputPackage source;
        public Evaluation evaluation;
        public Instant createdAt,updatedAt,checkoutAt;
        public UUID checkoutRequestId,replanRequestId;
        public int checkoutFromVersion,replanFromVersion;
        public com.nutrimarket.nutrimarket.model.planning.PlanningModels.Replan replanRequest;
        // REQUESTED while Agent 3 replans affected meals; ESCALATED once the replan limit sends this basket to a Human Expert (PM-06).
        public String replanStatus;
        public List<MaterialChange> materialChanges=new ArrayList<>();
        // Soft defaults learned from earlier baskets (PM-09). They are editable and never become hard constraints.
        public List<String> learnedSignals=new ArrayList<>();
        // UC-05 step 5: why the first draft uses a different fulfilment, preference or budget than the defaults; null when unchanged.
        public String defaultChoice;
        // When the basket is over budget: the lowest total of any complete basket for these meals, and how to get it.
        public BigDecimal minimumTotal;
        public String minimumChoice;
        public List<Event> audit=new ArrayList<>();
    }
    public enum StoreStatus { PENDING, ACCEPTED, PARTIAL, REJECTED, CANCELLED }
    public record StoreLine(String productId,String sku,String name,BigDecimal packages,BigDecimal subtotal,boolean accepted,String reason) {}
    public record StoreOrder(String store,Fulfilment fulfilment,StoreStatus status,int attempts,String retailerReference,
                             List<StoreLine> lines,BigDecimal fee,String reason,String fulfilmentDetail,Instant updatedAt) {}
    // A submission replaced after re-optimisation, kept for traceability.
    public record Submission(int number,Instant at,String status,BigDecimal total,List<StoreOrder> storeOrders) {}
    /** Where home-delivery orders go. Kept apart from the health profile: never sent to Qwen, experts or store reports. */
    public record DeliveryAddress(@NotBlank @Size(max=100) String recipient,@NotBlank @Size(max=200) String line1,@Size(max=200) String line2,
                                  @NotBlank @Size(max=100) String suburb,@NotBlank @Pattern(regexp="NSW|VIC|QLD|SA|WA|TAS|ACT|NT") String state,
                                  @NotBlank @Pattern(regexp="\\d{4}",message="Enter a 4-digit Australian postcode.") String postcode,@Size(max=300) String instructions) {
        public String text(){return recipient+", "+line1+(line2==null||line2.isBlank()?"":", "+line2)+", "+suburb+" "+state+" "+postcode;}
    }
    public record AddressView(DeliveryAddress address) {}
    public static class Order {
        // Orders recorded before retailer submission existed keep LOCAL_CONFIRMED / LOCAL_CANCELLED and no mode.
        public String orderId,memberId,basketId,status,currency,mode;
        public UUID requestId;
        public int basketVersion,submission=1;
        public BigDecimal total;
        public Instant createdAt,cancelledAt,updatedAt;
        public Basket snapshot;
        // UC-06 success condition: the order is stored with its plan, selected SKUs, preference signals and promotion decisions.
        public String planId,resultId,promotionDecision,qualityDecision;
        public Integer planVersion,marketRevision;
        public List<String> selectedSkus=new ArrayList<>();
        public Settings preferenceSignals;
        public List<StoreOrder> storeOrders=new ArrayList<>();
        public List<Submission> history=new ArrayList<>();
        // Home delivery only: the saved address when the order was placed.
        public DeliveryAddress deliveryAddress;
        public List<UUID> requestIds=new ArrayList<>();
        // Last recovery or re-optimisation request, so a repeated click acts once (UC-06 A4).
        public UUID actionRequestId;
        public List<Event> events=new ArrayList<>();
    }
    public record SubmitResult(Basket basket,Order order) {}
    public record RetailerLine(String productId,String sku,String name,BigDecimal packages) {}
    public record RetailerRequest(String orderId,String store,int attempt,Fulfilment fulfilment,List<RetailerLine> lines) {}
    public record RetailerResponse(String reference,StoreStatus status,List<String> rejectedSkus,String reason) {}
    public record BasketPage(List<Basket> items,int page,int size) {}
    public record OrderPage(List<Order> items,int page,int size) {}
    public record Demand(String sku,String productName,String currency,BigDecimal packages,BigDecimal value,BigDecimal promotedValue) {}
    // Pantry: shelf-stable leftovers from accepted orders, recomputed from order history plus the member's corrections.
    // remainingGrams is weight as sold (for example dry rice); edibleGrams converts it with the product's cooked-yield factor.
    public record PantryItem(String key,String ingredientId,String productId,String sku,String name,String store,
                             BigDecimal remainingGrams,BigDecimal yieldFactor,BigDecimal edibleGrams,Instant addedAt,String orderId,boolean adjusted) {}
    public record Purchase(String orderId,Instant orderedAt,String sku,String name,String store,BigDecimal packages,BigDecimal purchasedGrams,
                           BigDecimal neededGrams,BigDecimal leftoverGrams,boolean keptInPantry,BigDecimal fromPantryGrams) {}
    public record PantryView(List<PantryItem> stock,List<Purchase> lastWeek,Instant since) {}
    public record PantryAdjustment(@NotBlank @Size(max=100) String key,@NotNull @DecimalMin("0") @DecimalMax("100000") BigDecimal remainingGrams) {}
    public record PantryCorrection(String key,BigDecimal remainingGrams,Instant at) {}
    public static class PantryDocument { public List<PantryCorrection> corrections=new ArrayList<>(); }
    public record Report(String store,String mode,boolean suppressed,int minimumCohort,List<Demand> demand,String note) {}
}
