package com.nutrimarket.nutrimarket.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("nutrimarket.planning")
public record PlanningPolicy(@DefaultValue("planning-v1") @NotBlank String policyVersion,
    @DefaultValue("qwen-plus") @NotBlank String model,
    @DefaultValue("60s") Duration timeout,
    @DefaultValue("3") @Min(1) @Max(5) int maxRevisions,
    @DefaultValue("2") @Min(0) @Max(5) int maxReplans,
    @DefaultValue("") String marketEndpoint,
    @DefaultValue("") String marketApiKey,
    @DefaultValue("10s") Duration marketTimeout,
    @DefaultValue("3") @Min(1) @Max(10) int deliveryAttempts,
    // Upper bound on each substitute's nutrient deviation, as a fraction of the planned contribution.
    @DefaultValue("0.2") @DecimalMin("0") @DecimalMax("0.5") double substitutionTolerance) {
    @Override public String toString() { return "PlanningPolicy[credentials=REDACTED]"; }
}
