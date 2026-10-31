package com.nutrimarket.nutrimarket.config;

import com.nutrimarket.nutrimarket.model.health.HealthModels.Target;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("nutrimarket.health.profile-defaults")
public record HealthProfileDefaults(@NotBlank @Size(max=500) String targetSource,
        @NotNull @Size(min=4,max=8) Map<String,@NotNull @Valid Target> targets) {}
