package com.nutrimarket.nutrimarket.service.pipeline;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="nutrimarket.pipeline.enabled",havingValue="true",matchIfMissing=true)
public class MealPipelineScheduler {
    private final MealPipeline pipeline;
    public MealPipelineScheduler(MealPipeline pipeline){this.pipeline=pipeline;}
    @Scheduled(fixedDelayString="${nutrimarket.pipeline.delay-ms:10000}",initialDelayString="${nutrimarket.pipeline.delay-ms:10000}")
    public void advance(){pipeline.advanceAll();}
}
