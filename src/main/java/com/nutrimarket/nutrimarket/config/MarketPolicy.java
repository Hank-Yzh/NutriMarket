package com.nutrimarket.nutrimarket.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("nutrimarket.market")
public record MarketPolicy(@DefaultValue("market-v1") String policyVersion,
    @DefaultValue("LOCAL") Source source,@DefaultValue("") String catalogueEndpoint,
    @DefaultValue("") String catalogueApiKey,@DefaultValue("15s") Duration catalogueTimeout,
    @DefaultValue("24h") Duration maxAge,@DefaultValue("3") @Min(1) @Max(10) int maxRefreshes,
    @DefaultValue("20") @Min(1) @Max(50) int candidateLimit,
    @DefaultValue("true") boolean semanticEnabled,@DefaultValue("qwen-plus") String model,
    @DefaultValue("30s") Duration modelTimeout,@DefaultValue("0.85") @DecimalMin("0.01") @DecimalMax("1") double confidenceThreshold,
    @DefaultValue List<String> operatorUsernames,
    @DefaultValue("") String procurementEndpoint,@DefaultValue("") String procurementApiKey,
    @DefaultValue("10s") Duration procurementTimeout,@DefaultValue("3") @Min(1) @Max(10) int deliveryAttempts) {
    public enum Source { LOCAL, HTTP }
    @Override public String toString(){return "MarketPolicy[credentials=REDACTED]";}
}
