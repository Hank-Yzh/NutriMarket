package com.nutrimarket.nutrimarket.service.health;

import com.nutrimarket.nutrimarket.dao.health.HealthMapper;
import java.time.*;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Period key makes catch-up after restart idempotent; each member uses their own timezone. */
@Component
@ConditionalOnProperty(name="nutrimarket.health.scheduler-enabled",havingValue="true",matchIfMissing=true)
public class HealthScheduler {
    private final HealthService service;private final HealthMapper mapper;private final Clock clock;
    public HealthScheduler(HealthService service,HealthMapper mapper,Clock clock) {this.service=service;this.mapper=mapper;this.clock=clock;}
    @Scheduled(fixedDelayString="${nutrimarket.health.schedule-delay-ms:3600000}",initialDelayString="${nutrimarket.health.schedule-initial-delay-ms:60000}")
    public void assessPreviousWeek() {
        String after="";
        while(true) {
            var members=mapper.members(after);if(members.isEmpty()) return;
            for(String member:members) {
                try {
                    var p=service.profile(member);
                    var start=clock.instant().atZone(ZoneId.of(p.information().timezone())).toLocalDate()
                            .minusDays(7);
                    service.generate(member,start);
                } catch(Exception e) {LoggerFactory.getLogger(getClass()).warn("Weekly health assessment deferred: {}",e.getClass().getSimpleName());}
            }
            after=members.get(members.size()-1);
        }
    }
}
