package com.nutrimarket.nutrimarket.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.validation.annotation.Validated;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({AgentConfiguration.Policy.class, AgentConfiguration.Integrations.class, HealthPolicy.class, HealthProfileDefaults.class, PlanningPolicy.class, MarketPolicy.class, ProcurementPolicy.class})
public class AgentConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }

    @Validated
    @ConfigurationProperties("nutrimarket.dietary")
    public record Policy(@DecimalMin("0.0") @DecimalMax("1.0") double confidenceThreshold,
                         @NotBlank String policyVersion, @Min(1) int maxAttempts,
                         @NotNull Duration confirmationTtl, @Min(1) int minImageSide,
                         @Min(1) long maxImagePixels, @Min(1) long maxImageBytes) {}

    @ConfigurationProperties("nutrimarket.integrations")
    public record Integrations(@DefaultValue @Valid Vision vision,
                               @DefaultValue @Valid Nutrition nutrition,
                               @DefaultValue @Valid HealthAgent healthAgent) {}
    public record Vision(@DefaultValue("https://dashscope.aliyuncs.com/compatible-mode/v1") String baseUrl,
                         @DefaultValue("qwen-vl-plus") String model,
                         @DefaultValue("") String apiKey, @DefaultValue("60s") Duration timeout) {}
    public record Nutrition(@DefaultValue("https://api.nal.usda.gov/fdc/v1") String baseUrl,
                            @DefaultValue("") String apiKey, @DefaultValue("20s") Duration timeout) {}
    public record HealthAgent(@DefaultValue("") String endpoint, @DefaultValue("") String apiKey,
                              @DefaultValue("10s") Duration timeout, @DefaultValue("3") int maxAttempts) {}
}
