package com.nutrimarket.nutrimarket.config;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("nutrimarket.health")
public record HealthPolicy(@DefaultValue("health-rolling-v3") @NotBlank String policyVersion,
    @DefaultValue("3") @Min(1) @Max(10) int mealsPerDay,
    @DefaultValue("3") @Min(1) @Max(7) int persistentDays,
    @DefaultValue("0.5") @DecimalMin("0.01") @DecimalMax("0.99") BigDecimal reviewLowRatio,
    @DefaultValue("1.5") @DecimalMin("1.01") @DecimalMax("10") BigDecimal reviewHighRatio,
    @DefaultValue List<String> expertUsernames,
    // Conditions that are routine context for diet planning. Any other or unrecognised condition needs an expert (exact match, case-insensitive).
    @DefaultValue({"type 2 diabetes","prediabetes","high blood pressure","hypertension","high cholesterol","overweight","obesity"}) List<String> routineConditions,
    @DefaultValue("qwen-plus") String summaryModel,
    @DefaultValue("30s") Duration summaryTimeout) {}
