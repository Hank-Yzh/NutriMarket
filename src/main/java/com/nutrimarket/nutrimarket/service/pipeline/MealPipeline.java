package com.nutrimarket.nutrimarket.service.pipeline;

import com.nutrimarket.nutrimarket.config.PlanningPolicy;
import com.nutrimarket.nutrimarket.config.MarketPolicy;
import com.nutrimarket.nutrimarket.dao.health.HealthMapper;
import com.nutrimarket.nutrimarket.dao.market.MarketMapper;
import com.nutrimarket.nutrimarket.dao.planning.PlanningMapper;
import com.nutrimarket.nutrimarket.dao.procurement.ProcurementMapper;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.model.health.HealthModels.ProfileInput;
import com.nutrimarket.nutrimarket.model.market.MarketModels;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.*;
import com.nutrimarket.nutrimarket.service.health.HealthService;
import com.nutrimarket.nutrimarket.service.market.MarketDelivery;
import com.nutrimarket.nutrimarket.service.market.MarketService;
import com.nutrimarket.nutrimarket.service.planning.PlanningDelivery;
import com.nutrimarket.nutrimarket.service.planning.PlanningService;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Carries a cleared assessment through meal planning and market matching to a basket without member clicks,
 * under the profile's standing planning consent. Each step stays idempotent, so a restart or a parallel outbox run is harmless.
 */
@Service
public class MealPipeline {
    private static final Logger log=LoggerFactory.getLogger(MealPipeline.class);
    public enum Stage { NO_PROFILE, NO_ASSESSMENT, CONSENT_REQUIRED, PLANNING, PLAN_PAUSED, MATCHING, MATCH_PAUSED, BUILDING_BASKET, BASKET_PAUSED, BASKET_READY }
    public record Status(Stage stage,String message,String assessmentId,LocalDate periodStart,String planId,String planState,
                         List<String> planIssues,String resultId,String marketState,String basketId) {}
    private final HealthMapper healthMapper;private final HealthService health;private final PlanningService planning;private final PlanningMapper plans;
    private final MarketMapper markets;private final MarketService market;private final ProcurementMapper baskets;
    private final ObjectProvider<PlanningDelivery> planningDelivery;private final ObjectProvider<MarketDelivery> marketDelivery;private final Clock clock;private final PlanningPolicy policy;
    private final MarketPolicy marketPolicy;
    public MealPipeline(HealthMapper healthMapper,HealthService health,PlanningService planning,PlanningMapper plans,MarketMapper markets,MarketService market,
                        ProcurementMapper baskets,ObjectProvider<PlanningDelivery> planningDelivery,ObjectProvider<MarketDelivery> marketDelivery,Clock clock,PlanningPolicy policy,MarketPolicy marketPolicy) {
        this.healthMapper=healthMapper;this.health=health;this.planning=planning;this.plans=plans;this.markets=markets;this.market=market;
        this.baskets=baskets;this.planningDelivery=planningDelivery;this.marketDelivery=marketDelivery;this.clock=clock;this.policy=policy;
        this.marketPolicy=marketPolicy;
    }
    public void advanceAll() {
        for(String member:healthMapper.clearedMembers()) {
            try {advance(member);}
            catch(RuntimeException e) {log.warn("Meal pipeline deferred for a member: {}",e instanceof AgentException a?a.code():e.getClass().getSimpleName());}
        }
        // Published plans and finished matches move on through the existing outboxes; running them now saves a polling interval.
        planningDelivery.ifAvailable(PlanningDelivery::deliverPending);
        marketDelivery.ifAvailable(MarketDelivery::deliverPending);
    }
    /** Generates and publishes a plan for the member's latest cleared assessment; matching and the basket follow via the outboxes. */
    public void advance(String member) {
        var row=healthMapper.latestCleared(member);if(row==null)return;
        var info=health.profile(member).information();if(!info.automaticPlanning())return;
        var a=health.get(member,row.assessmentId());if(a.stale)return;
        LocalDate tomorrow=clock.instant().atZone(ZoneId.of(info.timezone())).toLocalDate().plusDays(1);
        var plan=latestPlan(member,a.assessmentId);
        // Regenerate when the assessment gained evidence or the planning policy changed; an expired week waits for the next assessment.
        if(plan==null || plan.state==State.STALE && (plan.assessmentRevision!=a.revision || !plan.policyVersion.equals(policy.policyVersion()) && !plan.weekEnd.isBefore(tomorrow)))
            plan=planning.generate(member,new Generate(UUID.fromString(a.assessmentId),plan==null||plan.weekStart.isBefore(tomorrow)?tomorrow:plan.weekStart,true));
        if(plan.state==State.DRAFT)plan=planning.publishAutomatically(member,plan.planId,plan.version);
        if(plan.state==State.PUBLISHED)refreshMatching(member,plan);
    }
    /** A catalogue change cancels the old handoff. Resume matching under the existing bounded refresh budget. */
    private void refreshMatching(String member,Plan plan) {
        var found=markets.forPlan(plan.planId,plan.version);
        if(found==null||baskets.forResult(found.resultId())!=null)return;
        var result=market.get(member,found.resultId());
        if(result.planStale||result.refreshCount>=marketPolicy.maxRefreshes())return;
        if(result.state!=MarketModels.State.STALE&&result.state!=MarketModels.State.INTERRUPTED)return;
        market.refresh(member,result.resultId,new MarketModels.Refresh(UUID.randomUUID(),result.revision,
            result.planning.weeklyMealPlan().stream().map(Meal::menuLineId).toList()));
    }
    public Status status(String member) {
        ProfileInput info;
        try {info=health.profile(member).information();}
        catch(AgentException e) {return status(Stage.NO_PROFILE,"Complete your health profile to start.",null,null,null,null);}
        var row=healthMapper.latestCleared(member);
        if(row==null) return status(Stage.NO_ASSESSMENT,"Your basket is prepared automatically once a health assessment is cleared for planning.",null,null,null,null);
        if(!info.automaticPlanning()) return status(Stage.CONSENT_REQUIRED,"Turn on automatic meal planning in your health profile to build baskets automatically.",row.assessmentId(),row.periodStart(),null,null);
        var plan=latestPlan(member,row.assessmentId());
        if(plan==null) return status(Stage.PLANNING,"Planning meals from your assessment…",row.assessmentId(),row.periodStart(),null,null);
        return switch(plan.state) {
            case DEFERRED,TERMINATED -> status(Stage.PLAN_PAUSED,"Meal planning paused. Open the plan to see why and retry.",row.assessmentId(),row.periodStart(),plan,null);
            case PUBLISHED -> matching(member,row.assessmentId(),row.periodStart(),plan);
            case STALE -> plan.assessmentRevision==health.get(member,row.assessmentId()).revision&&plan.policyVersion.equals(policy.policyVersion())
                ? status(Stage.PLAN_PAUSED,"This meal plan's week has passed. A new plan starts with your next cleared assessment.",row.assessmentId(),row.periodStart(),plan,null)
                : status(Stage.PLANNING,"Updating your meal plan from new records…",row.assessmentId(),row.periodStart(),plan,null);
            default -> status(Stage.PLANNING,"Planning meals from your assessment…",row.assessmentId(),row.periodStart(),plan,null);
        };
    }
    private Status matching(String member,String assessmentId,LocalDate period,Plan plan) {
        var found=markets.forPlan(plan.planId,plan.version);
        if(found==null) {
            var handoff=plans.delivery(plan.planId,plan.version);
            if(handoff==null||"DELIVERY_FAILED".equals(handoff.state())||"CANCELLED".equals(handoff.state()))
                return status(Stage.MATCH_PAUSED,"Automatic product matching could not start. Open matching to try again.",assessmentId,period,plan,null);
            return status(Stage.MATCHING,"Matching products to your meal plan…",assessmentId,period,plan,null);
        }
        var result=market.get(member,found.resultId());var basket=baskets.forResult(result.resultId);
        if(basket==null) {
            if(result.state==MarketModels.State.MATCHING)
                return status(Stage.MATCHING,"Matching products to your meal plan…",assessmentId,period,plan,result);
            if(result.state==MarketModels.State.STALE||result.state==MarketModels.State.INTERRUPTED) {
                String reason=result.state==MarketModels.State.STALE?"Product information changed or expired.":"Product matching was interrupted.";
                String next=result.refreshCount<marketPolicy.maxRefreshes()?" Matching will retry automatically.":" The automatic refresh limit has been reached. Open matching for details.";
                return status(Stage.MATCH_PAUSED,reason+next,assessmentId,period,plan,result);
            }
            if(!"QUEUED".equals(result.deliveryState))
                return status(Stage.BASKET_PAUSED,"Your matches are available, but the basket could not be created. Try building it again.",assessmentId,period,plan,result);
            return status(Stage.BUILDING_BASKET,"Products matched. Building your basket…",assessmentId,period,plan,result);
        }
        return new Status(Stage.BASKET_READY,"Your basket is ready.",assessmentId,period,plan.planId,plan.state.name(),plan.issues,result.resultId,result.state.name(),basket.id());
    }
    private Plan latestPlan(String member,String assessmentId){var row=plans.latestForAssessment(member,assessmentId);return row==null?null:planning.get(member,row.planId());}
    private static Status status(Stage stage,String message,String assessmentId,LocalDate period,Plan plan,MarketModels.MarketResult result) {
        return new Status(stage,message,assessmentId,period,plan==null?null:plan.planId,plan==null?null:plan.state.name(),plan==null?List.of():plan.issues,
            result==null?null:result.resultId,result==null?null:result.state.name(),null);
    }
}
