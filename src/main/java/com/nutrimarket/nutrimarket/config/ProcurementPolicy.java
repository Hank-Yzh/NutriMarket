package com.nutrimarket.nutrimarket.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.util.Map;

@Validated
@ConfigurationProperties("nutrimarket.procurement")
public record ProcurementPolicy(@DefaultValue("procurement-v4") String policyVersion,
    @DefaultValue("128") @Min(16) @Max(512) int searchWidth,
    @DefaultValue("5") @Min(1) @Max(10) int checkoutMinutes,
    @DefaultValue("5") @Min(5) int minimumCohort,
    // Checkout refreshes allowed per basket before expert review; Agent 4's manual refresh budget is separate.
    @DefaultValue("20") @Min(1) @Max(100) int maxCheckouts,
    @DefaultValue Map<String,String> reportStores,
    // Near-equivalence margin within which promotion labels may break ties (PM-04).
    @DefaultValue("0.02") @DecimalMin("0") @DecimalMax("0.2") double promotionTieMargin,
    // Evidence age Procurement accepts from Agent 4 at checkout, sent as requiredFreshAfter.
    @DefaultValue("24h") Duration checkoutEvidenceAge,
    // Operator-recorded store fees. Pickup is free unless configured; an unlisted delivery fee stays unquoted.
    @DefaultValue List<@Valid StoreFees> storeFees,
    // Balanced may spend up to this fraction above the lowest-cost basket on higher recorded range tiers.
    @DefaultValue("0.15") @DecimalMin("0") @DecimalMax("1") double balancedMargin,
    @DefaultValue @Valid Retailer retailer,
    // Street address of each simulated partner store, shown for pick-up and on store orders.
    @DefaultValue Map<String,String> storeAddresses) {
    public record StoreFees(@NotBlank String store,@NotNull @Pattern(regexp="[A-Z]{3}") String currency,
                            @DecimalMin("0") BigDecimal delivery,@DecimalMin("0") BigDecimal pickup) {}
    /**
     * Simulated partner stores (no real supermarket API). Every store accepts unless listed in simulated-outcomes,
     * which lets a demonstration show rejected or partial orders (UC-06 A4). max-attempts bounds submissions per store order.
     */
    public record Retailer(@DefaultValue("3") @Min(1) @Max(10) int maxAttempts,@DefaultValue List<@Valid SimulatedOutcome> simulatedOutcomes) {}
    public enum Outcome { REJECT, PARTIAL }
    // PARTIAL rejects only the listed SKUs. firstAttemptOnly makes the failure transient, so Recovery succeeds.
    public record SimulatedOutcome(@NotBlank String store,@NotNull Outcome outcome,@DefaultValue List<String> skus,
                                   @DefaultValue("The store could not accept this order.") String reason,@DefaultValue("false") boolean firstAttemptOnly) {}
}
