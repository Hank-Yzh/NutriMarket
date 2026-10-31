package com.nutrimarket.nutrimarket.demo;

import com.nutrimarket.nutrimarket.dao.auth.UserMapper;
import com.nutrimarket.nutrimarket.dao.health.HealthMapper;
import com.nutrimarket.nutrimarket.dao.market.MarketMapper;
import com.nutrimarket.nutrimarket.integration.dietary.NutritionClient;
import com.nutrimarket.nutrimarket.integration.market.MarketSemanticClient;
import com.nutrimarket.nutrimarket.integration.planning.PlanningClient;
import com.nutrimarket.nutrimarket.model.auth.AuthModels;
import com.nutrimarket.nutrimarket.model.dietary.DietaryModels.*;
import com.nutrimarket.nutrimarket.model.health.HealthModels.*;
import com.nutrimarket.nutrimarket.model.market.MarketModels.*;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.*;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.State;
import com.nutrimarket.nutrimarket.model.procurement.ProcurementModels.*;
import com.nutrimarket.nutrimarket.service.demo.DemoScenarios;
import com.nutrimarket.nutrimarket.service.health.HealthService;
import com.nutrimarket.nutrimarket.service.market.*;
import com.nutrimarket.nutrimarket.service.planning.*;
import com.nutrimarket.nutrimarket.service.procurement.ProcurementService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The demo scenarios end to end on the shipped fixtures: demo-scenarios.json, test-catalogue.json and pinned USDA values.
 * Agents 3-5 run unmodified; only the external services (USDA, Qwen) are replaced, and Agent 2's assessment is inserted.
 */
@ActiveProfiles("test")
@SpringBootTest(properties={
    "spring.config.import=","debug=false",
    "spring.datasource.url=jdbc:h2:mem:demo-scenario-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.sql.init.schema-locations=classpath:schema-h2.sql",
    "nutrimarket.procurement.report-stores.reviewer=FreshMart Central","nutrimarket.health.expert-usernames[0]=reviewer",
    "nutrimarket.dietary.delivery-delay-ms=3600000"})
class DemoScenarioTests {
    static final Instant NOW=Instant.parse("2026-10-14T00:00:00Z");
    static final LocalDate WEEK=LocalDate.parse("2026-10-19");
    @Autowired DemoScenarios scenarios;@Autowired ProcurementService procurement;@Autowired PlanningService planning;@Autowired MarketService market;
    @Autowired CatalogService catalog;@Autowired MarketMapper markets;@Autowired IngredientCatalog ingredients;
    @Autowired HealthService health;@Autowired HealthMapper healthMapper;@Autowired UserMapper users;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    @MockitoBean Clock clock;@MockitoBean NutritionClient nutrition;@MockitoBean PlanningClient model;@MockitoBean MarketSemanticClient matcher;
    int next=0;

    @BeforeEach void setup() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        for(String table:List.of("member_pantry","procurement_order","shopping_basket","market_outbox","market_result","market_product_history","market_product","planning_outbox","nutrition_plan","weekly_health_assessment","health_intake","member_health_profile","dietary_outbox","dietary_record","member_account"))jdbc.update("DELETE FROM "+table);
        var reference=json.readTree(new ClassPathResource("usda-reference.json").getInputStream()).path("foods");
        when(nutrition.lookup(any())).thenAnswer(call->{FoodInput f=call.getArgument(0);var food=reference.path(String.valueOf(f.fdcId()));Map<String,Nutrient> values=new TreeMap<>();
            food.path("per100g").properties().forEach(e->values.put(e.getKey(),new Nutrient(e.getValue().decimalValue(),e.getKey().equals("1008")?"kcal":e.getKey().equals("1093")?"mg":"g")));
            return new NutritionMatch(f.fdcId(),f.searchTerm(),BigDecimal.ONE,values,new Provenance("USDA FoodData Central",String.valueOf(f.fdcId()),"https://fdc.nal.usda.gov/food-details/"+f.fdcId()+"/nutrients",NOW));});
        // Every catalogue candidate is confirmed as the requested food, like the verified catalogue mapping; composition checks still apply.
        when(matcher.match(any(),anyList())).thenAnswer(call->{List<Product> products=call.getArgument(1);Map<String,BigDecimal> values=new HashMap<>();products.forEach(p->values.put(p.productId(),BigDecimal.ONE));return values;});
        new TestCatalogueLoader(catalog,markets,json,clock).load();
        users.insert(new AuthModels.UserAccount(UUID.randomUUID().toString(),"reviewer","unused",true,NOW));
    }
    /** A member with the scenario's profile and a cleared assessment of the demo week. */
    String member(DemoScenarios.Member m) {
        String id=new UUID(0,++next).toString();users.insert(new AuthModels.UserAccount(id,m.username(),"unused",true,NOW));
        var profile=health.saveProfile(id,scenarios.profile(m,0));var info=profile.information();
        var a=new Assessment();a.assessmentId=UUID.randomUUID().toString();a.memberId=id;a.profileVersion=profile.version();a.periodStart=scenarios.fixture().assessmentWeek();a.periodEnd=a.periodStart.plusDays(6);
        a.timezone=info.timezone();a.policyVersion="health-demo-v1";a.status=AssessmentStatus.COMPLETED;a.planningClearance=Clearance.CLEARED;a.riskStatus=Risk.TARGET_DEVIATION;a.createdAt=NOW;a.updatedAt=NOW;
        a.healthConstraintSet=new Constraints(info.allergies(),info.restrictions(),info.conditions(),info.targets(),List.of());healthMapper.insertAssessment(a,json.writeValueAsString(a));
        return id;
    }
    DemoScenarios.Run run(String username){var m=scenarios.member(username);var id=member(m);var a=healthMapper.latestCleared(id);return scenarios.run(id,m,a.assessmentId(),WEEK);}
    static String report(DemoScenarios.Run run){return run.checks().stream().map(c->(c.passed()?"PASS ":"FAIL ")+c.description()+": "+c.evidence()).collect(Collectors.joining("\n"));}
    static List<ProductMatch> candidates(MarketResult r,String sku){return r.requirements.stream().flatMap(q->q.candidates().stream()).filter(x->x.product().facts().sku().equals(sku)).toList();}
    static Set<String> selected(Basket b){return b.evaluation.lines().stream().map(l->l.product().facts().sku()).collect(Collectors.toSet());}

    @Autowired com.nutrimarket.nutrimarket.config.ProcurementPolicy procurementPolicy;
    @Test void everyCatalogueStoreHasASydneyAddress() {
        var stores=catalog.list(1,200).items().stream().map(p->p.facts().store()).collect(Collectors.toSet());
        assertThat(stores).hasSize(3).allSatisfy(store->assertThat(procurementPolicy.storeAddresses().get(store)).as(store).isNotNull().matches(".+, .+ NSW 2\\d{3}"));
    }
    @Test void fixedPlansPassAgent3ValidationAndShareTheirBaseWeek() {
        var plans=scenarios.fixture().plans();
        assertThat(plans.get("chicken-week").meals()).hasSize(21);assertThat(plans.get("seafood-week").meals()).hasSize(21);
        var chicken=plans.get("chicken-week").meals();var seafood=plans.get("seafood-week").meals();
        for(int i=0;i<21;i++){boolean protein=seafood.get(i).ingredients().stream().anyMatch(p->Set.of("prawns","salmon").contains(p.ingredientId()));
            if(!protein)assertThat(seafood.get(i)).as("meal %d outside the seafood dinners",i).isEqualTo(chicken.get(i));}
        // The plans are published as written: no portion tuning was needed to meet every daily target.
        var run=run("demo_ready");assertThat(run.plan().audit).noneMatch(e->e.action().equals("PORTIONS_ADJUSTED"));
        assertThat(run.plan().attempts).isEqualTo(1);assertThat(run.plan().model).isEqualTo("demo fixture");verifyNoInteractions(model);
    }
    @Test void rerunAfterACatalogueRestampRechecksEvidenceAndRecalculatesTheSameBasket() {
        var m=scenarios.member("demo_ready");var id=member(m);var assessment=healthMapper.latestCleared(id).assessmentId();
        var first=scenarios.run(id,m,assessment,WEEK);assertThat(first.passed()).as(report(first)).isTrue();
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(13)));assertThat(new TestCatalogueLoader(catalog,markets,json,clock).load()).isPositive();
        var again=scenarios.run(id,m,assessment,WEEK);
        assertThat(again.passed()).as(report(again)).isTrue();assertThat(again.plan().planId).isEqualTo(first.plan().planId);assertThat(again.market().revision).isEqualTo(2);
        assertThat(again.basket().basketId).isEqualTo(first.basket().basketId);assertThat(again.basket().state).isEqualTo(com.nutrimarket.nutrimarket.model.procurement.ProcurementModels.State.DRAFT);
    }
    @Test void demo000NormalFlowMatchesEveryIngredientAndBuildsACompleteBasket() {
        var run=run("demo_ready");
        assertThat(run.passed()).as(report(run)).isTrue();
        assertThat(run.market().readyForProcurement).isTrue();
        assertThat(run.market().requirements.stream().map(RequirementResult::ingredientId).distinct()).hasSize(30);
        assertThat(run.basket().evaluation.unfulfilledMenuLines()).isEmpty();
    }
    @Test void demo001UnknownAllergenEvidenceAndCrossContactSkusAreNeverSelected() {
        var normal=run("demo_ready");var allergy=run("demo_allergy");
        assertThat(allergy.passed()).as(report(allergy)).isTrue();
        // Missing allergen statement: UNKNOWN for every member, so no basket may use it.
        for(var r:List.of(normal.market(),allergy.market()))assertThat(candidates(r,"VB-CHK-1000")).isNotEmpty().allSatisfy(x->{assertThat(x.hardConstraintStatus()).isEqualTo(Compliance.UNKNOWN);assertThat(x.eligible()).isFalse();
            assertThat(x.reasons()).contains("Complete allergen and may-contain evidence is missing.");});
        // "May contain sesame, peanut" is eligible for DEMO-000 but blocked for the member with those allergies.
        assertThat(candidates(normal.market(),"GL-CHK-STRIP-500")).allSatisfy(x->assertThat(x.hardConstraintStatus()).isEqualTo(Compliance.COMPLIANT));
        assertThat(candidates(allergy.market(),"GL-CHK-STRIP-500")).isNotEmpty().allSatisfy(x->{assertThat(x.hardConstraintStatus()).isEqualTo(Compliance.NON_COMPLIANT);assertThat(x.reasons()).anyMatch(y->y.contains("Sesame"));});
        assertThat(candidates(allergy.market(),"GL-OAT-500")).isNotEmpty().allSatisfy(x->assertThat(x.hardConstraintStatus()).isEqualTo(Compliance.NON_COMPLIANT));
        assertThat(selected(normal.basket())).doesNotContain("VB-CHK-1000");
        assertThat(selected(allergy.basket())).doesNotContain("VB-CHK-1000","GL-CHK-STRIP-500","GL-OAT-500","GL-SPIN-250");
        assertThat(allergy.basket().evaluation.lines()).anyMatch(l->l.product().facts().ingredientId().equals("chicken"));
    }
    @Test void demo002OutOfStockSeafoodIsReplacedByAnEligiblePlannerSubstitute() {
        var run=run("demo_substitute");
        assertThat(run.passed()).as(report(run)).isTrue();
        var seafood=run.market().requirements.stream().filter(q->Set.of("prawns","salmon").contains(q.ingredientId())).toList();
        assertThat(seafood).hasSize(4).allSatisfy(q->{
            assertThat(q.candidates().stream().filter(x->x.product().facts().ingredientId().equals(q.ingredientId())&&x.product().facts().availability()==Availability.OUT_OF_STOCK)).isNotEmpty().allMatch(x->x.outcome()==Outcome.UNAVAILABLE);
            assertThat(q.checkedSubstitutes()).isNotEmpty();assertThat(q.outcome()).isEqualTo(Outcome.MATCHED);});
        var bought=run.basket().evaluation.lines().stream().flatMap(l->l.allocations().stream()).filter(a->Set.of("prawns","salmon").contains(a.plannedIngredientId())).toList();
        assertThat(bought).hasSize(4).allSatisfy(a->assertThat(a.evidence().product().facts().ingredientId()).isIn("chicken","tofu"));
    }
    @Test void demo003NoPermittedProductLeadsToAReplanRequestThatAgent3Resolves() {
        var run=run("demo_replan");
        assertThat(run.passed()).as(report(run)).isTrue();
        var b=run.basket();var lines=List.of("d0-DINNER","d2-DINNER","d4-DINNER","d6-DINNER");
        assertThat(b.evaluation.unfulfilledMenuLines()).containsExactlyInAnyOrderElementsOf(lines);
        // A pescatarian with a soy allergy may only swap salmon and prawns for each other, and both are out of stock.
        assertThat(run.market().requirements.stream().filter(q->lines.contains(q.menuLineId())&&Set.of("prawns","salmon").contains(q.ingredientId())))
            .hasSize(4).allSatisfy(q->{assertThat(q.outcome()).isEqualTo(Outcome.UNAVAILABLE);assertThat(q.checkedSubstitutes()).hasSize(1).doesNotContain("chicken","tofu");});
        // Agent 5 sends the ReplanRequest; Agent 3 (model replaced here) rewrites only those dinners without the unavailable seafood.
        var fixture=scenarios.fixture().plans().get("chicken-week").meals();
        var vegetarian=fixture.stream().filter(m->m.slot()==Slot.DINNER&&m.day()%2==1).toList();
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenAnswer(call->{List<String> affected=call.getArgument(5);
            return new Candidate(affected.stream().map(line->{int day=line.charAt(1)-'0';var meal=vegetarian.get((day/2)%vegetarian.size());return new CandidateMeal(day,Slot.DINNER,meal.ingredients(),meal.dish());}).toList());});
        var plan=procurement.replan(run.plan().memberId,b.basketId,new ReplanInput(b.version,UUID.randomUUID(),true));
        verify(model).propose(any(),anyString(),anyString(),argThat(catalog->!catalog.containsKey("prawns")&&!catalog.containsKey("salmon")&&!catalog.containsKey("tofu")&&!catalog.containsKey("chicken")),any(),argThat(affected->affected.containsAll(lines)&&affected.size()==4),anyList());
        var request=plan.replans.get(0);
        assertThat(request.reason()).isEqualTo(ReplanReason.AVAILABILITY);assertThat(request.unavailableIngredientIds()).containsExactlyInAnyOrder("prawns","salmon");
        assertThat(request.menuLineIds()).containsExactlyInAnyOrderElementsOf(lines);
        assertThat(plan.state).as(String.valueOf(plan.issues)).isEqualTo(State.DRAFT);
        var published=planning.publish(plan.memberId,plan.planId,new Publish(plan.version,true));
        var result=market.start(plan.memberId,new com.nutrimarket.nutrimarket.model.market.MarketModels.Start(UUID.fromString(published.planId),published.version));
        var replanned=procurement.receive(plan.memberId,result.resultId,result.revision);
        assertThat(result.readyForProcurement).isTrue();assertThat(replanned.evaluation.unfulfilledMenuLines()).isEmpty();
        assertThat(replanned.evaluation.lines()).noneMatch(l->Set.of("prawns","salmon","chicken","tofu").contains(l.product().facts().ingredientId()));
    }
    @Test void fiveShopperOrdersFillTheStoreDemandReport() {
        for(int i=1;i<=5;i++){var run=run("demo_shopper"+i);assertThat(run.passed()).as(report(run)).isTrue();
            var checks=scenarios.verifyOrder(scenarios.order(run.plan().memberId,run.basket()));assertThat(checks).allMatch(DemoScenarios.Check::passed);}
        var report=procurement.report(users.findByUsername("reviewer").id());
        assertThat(report.suppressed()).isFalse();assertThat(report.demand()).isNotEmpty().allSatisfy(d->assertThat(d.packages()).isPositive());
    }
}
