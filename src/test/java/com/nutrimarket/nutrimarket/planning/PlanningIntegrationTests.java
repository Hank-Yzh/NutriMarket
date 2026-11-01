package com.nutrimarket.nutrimarket.planning;

import com.nutrimarket.nutrimarket.config.PlanningPolicy;
import com.nutrimarket.nutrimarket.dao.auth.UserMapper;
import com.nutrimarket.nutrimarket.dao.health.HealthMapper;
import com.nutrimarket.nutrimarket.dao.planning.PlanningMapper;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.integration.dietary.NutritionClient;
import com.nutrimarket.nutrimarket.integration.planning.PlanningClient;
import com.nutrimarket.nutrimarket.model.auth.AuthModels;
import com.nutrimarket.nutrimarket.model.health.HealthModels.*;
import com.nutrimarket.nutrimarket.model.dietary.DietaryModels.*;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.*;
import com.nutrimarket.nutrimarket.service.auth.JwtTokenService;
import com.nutrimarket.nutrimarket.service.health.HealthService;
import com.nutrimarket.nutrimarket.service.planning.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.import=","debug=false","server.address=127.0.0.1",
    "spring.datasource.url=jdbc:h2:mem:planning-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.sql.init.schema-locations=classpath:schema-h2.sql",
    "nutrimarket.health.expert-usernames[0]=reviewer","nutrimarket.health.expert-usernames[1]=member",
    "nutrimarket.dietary.delivery-delay-ms=3600000"})
class PlanningIntegrationTests {
    static final String MEMBER="00000000-0000-0000-0000-000000000301",OTHER="00000000-0000-0000-0000-000000000302",EXPERT="00000000-0000-0000-0000-000000000303";
    static final Instant NOW=Instant.parse("2026-10-13T00:00:00Z");
    static final LocalDate WEEK=LocalDate.parse("2026-10-19");
    @Autowired PlanningService service;@Autowired PlanningMapper mapper;@Autowired PlanningEngine engine;@Autowired IngredientCatalog catalog;
    @Autowired HealthService health;@Autowired HealthMapper healthMapper;@Autowired UserMapper users;
    @Autowired JwtTokenService tokens;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    @MockitoBean Clock clock;@MockitoBean NutritionClient nutrition;@MockitoBean PlanningClient model;
    @Value("${local.server.port}") int port;
    String token,otherToken,expertToken;
    @BeforeEach void setup(){
        when(clock.instant()).thenReturn(NOW);
        for(String table:List.of("planning_outbox","nutrition_plan","weekly_health_assessment","health_intake","member_health_profile","dietary_outbox","dietary_record","member_account"))jdbc.update("DELETE FROM "+table);
        token=user(MEMBER,"member");otherToken=user(OTHER,"other");expertToken=user(EXPERT,"reviewer");
        when(nutrition.lookup(any())).thenAnswer(call->{FoodInput f=call.getArgument(0);return match(f==null?"Fixture":f.searchTerm(),"0.95");});
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(candidate("rice"));
    }
    String user(String id,String name){var user=new AuthModels.UserAccount(id,name,"unused",true,NOW);users.insert(user);return tokens.issue(user).accessToken();}
    static BigDecimal n(String s){return new BigDecimal(s);}
    static Map<String,Nutrient> nutrients(){return Map.of("1008",new Nutrient(n("100"),"kcal"),"1003",new Nutrient(n("10"),"g"),"1004",new Nutrient(n("3"),"g"),"1005",new Nutrient(n("15"),"g"));}
    static NutritionMatch match(String name,String confidence){return new NutritionMatch(123,name,n(confidence),nutrients(),new Provenance("USDA fixture","123","https://example.invalid/fixture",NOW));}
    static Map<String,Target> targets(){return Map.of("1008",new Target(n("550"),n("650"),"kcal"),"1003",new Target(n("55"),n("65"),"g"),"1004",new Target(n("15"),n("20"),"g"),"1005",new Target(n("85"),n("95"),"g"));}
    ProfileInput input(long version,List<String> allergies){return new ProfileInput(version,"Australia/Sydney","Test planning",allergies,List.of(),List.of(),"Simple meals","Synthetic targets for tests only",targets(),false);}
    Assessment assessment(List<String> allergies){return assessment(input(0,allergies));}
    Assessment assessment(ProfileInput in){
        var profile=health.saveProfile(MEMBER,in);var allergies=in.allergies();
        var a=new Assessment();a.assessmentId=UUID.randomUUID().toString();a.memberId=MEMBER;a.profileVersion=profile.version();a.periodStart=WEEK.minusWeeks(2);a.periodEnd=a.periodStart.plusDays(6);a.timezone="Australia/Sydney";a.policyVersion="health-demo-v1";a.status=AssessmentStatus.COMPLETED;a.planningClearance=Clearance.CLEARED;a.riskStatus=Risk.WITHIN_TARGETS;a.createdAt=NOW;a.updatedAt=NOW;a.healthConstraintSet=new Constraints(allergies,List.of(),List.of(),targets(),List.of());healthMapper.insertAssessment(a,json.writeValueAsString(a));return a;
    }
    static CandidateMeal meal(int d,Slot s,String ingredient){return new CandidateMeal(d,s,List.of(new Portion(ingredient,n("200"))));}
    static Candidate candidate(String ingredient,String grams){List<CandidateMeal> meals=new ArrayList<>();for(int day=0;day<7;day++)for(var slot:Slot.values())meals.add(new CandidateMeal(day,slot,List.of(new Portion(ingredient,n(grams)))));return new Candidate(meals);}
    static Candidate candidate(String ingredient){List<CandidateMeal> meals=new ArrayList<>();for(int day=0;day<7;day++)for(var slot:Slot.values())meals.add(meal(day,slot,ingredient));return new Candidate(meals);}
    Plan generate(Assessment a){return service.generate(MEMBER,new Generate(UUID.fromString(a.assessmentId),WEEK,true));}
    Plan publish(Plan p){return service.publish(MEMBER,p.planId,new Publish(p.version,true));}
    Replan replan(int version,String line,String ingredient){return new Replan(UUID.randomUUID(),version,ReplanReason.AVAILABILITY,"Supplier evidence: ingredient unavailable for this meal",List.of(line),List.of(ingredient),true);}
    HttpResponse<String> request(String method,String path,Object body,String bearer)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/planning"+path));if(bearer!=null)b.header("Authorization","Bearer "+bearer);
        b.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void generatesAndPublishesVersionedPackageIdempotently(){
        var a=assessment(List.of());var p=generate(a);assertThat(p.state).isEqualTo(State.DRAFT);assertThat(p.meals).hasSize(21);
        assertThat(p.dailyNutrition.get(0).nutrients().get("1008").amount()).isEqualByComparingTo("600");assertThat(generate(a).planId).isEqualTo(p.planId);
        publish(p);publish(p);var pack=service.packageFor(MEMBER,p.planId);
        assertThat(pack.ingredientRequirements()).hasSize(21);assertThat(pack.substitutionGroups()).allSatisfy(g->{assertThat(g.allowedIngredientIds()).first().isEqualTo("rice");
            // Fixture nutrition is identical for every food, so each alternative fits the day and is offered as a substitute.
            assertThat(g.substituteList()).extracting(Substitute::ingredientId).containsExactly("white_rice","quinoa","rice_noodles");assertThat(g.nutrientTolerances().get("1008").minimum()).isEqualByComparingTo("183.333334");assertThat(g.nutrientTolerances().get("1008").maximum()).isEqualByComparingTo("216.666666");
            assertThat(g.nutrientTolerances().get("1004").minimum()).isEqualByComparingTo("5");assertThat(g.nutrientTolerances().get("1004").maximum()).isEqualByComparingTo("6.666666");});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM planning_outbox",Integer.class)).isEqualTo(1);
        assertThat(service.get(MEMBER,p.planId).deliveryState).isEqualTo("QUEUED");verify(model,times(1)).propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList());
    }
    @Test void ingredientSourcesUsePinnedUsdaIds(){
        generate(assessment(List.of()));verify(nutrition).lookup(argThat(f->f!=null&&Long.valueOf(169704).equals(f.fdcId())));verify(nutrition,never()).lookup(argThat(f->f!=null&&f.fdcId()==null));
    }
    @Test void purchaseNamesStayIndependentOfCookedNutritionAndPortions(){
        var p=publish(generate(assessment(List.of())));var pack=service.packageFor(MEMBER,p.planId);
        assertThat(service.get(MEMBER,p.planId).purchaseIngredients).containsEntry("rice","Brown rice");
        assertThat(pack.ingredientRequirements()).allSatisfy(r->{
            assertThat(r.description()).isEqualTo("Brown rice");
            assertThat(r.quantity()).isEqualByComparingTo("200");
            assertThat(r.unit()).isEqualTo("g edible ready-to-eat weight");
        });
        assertThat(pack.nutritionSources().get("rice").ingredient().name()).isEqualTo("Cooked brown rice");
        assertThat(pack.dailyNutrition()).isEqualTo(p.dailyNutrition);
        assertThat(catalog.purchaseName("oats")).isEqualTo("Rolled oats");
        assertThat(catalog.purchaseName("chicken")).isEqualTo("Chicken breast");
        assertThat(catalog.purchaseName("lentils")).isEqualTo("Dried lentils");
        assertThat(catalog.purchaseName("yogurt")).isEqualTo("Plain low-fat yogurt");
        assertThat(catalog.all()).allSatisfy(i->assertThat(catalog.purchaseName(i.id())).doesNotContainIgnoringCase("cooked","boiled","roasted","steamed","baked","braised"));
    }
    @Test void legacyPublishedDescriptionsAreAdaptedWithoutRewritingSnapshot(){
        var p=publish(generate(assessment(List.of())));
        var document=json.readTree(mapper.delivery(p.planId,p.version).payload());
        for(var r:document.path("ingredientRequirements"))((ObjectNode)r).put("description","Cooked brown rice");
        String legacy=json.writeValueAsString(document);
        jdbc.update("UPDATE planning_outbox SET payload=? WHERE plan_id=?",legacy,p.planId);
        var pack=service.packageFor(MEMBER,p.planId);
        assertThat(pack.ingredientRequirements()).allSatisfy(r->assertThat(r.description()).isEqualTo("Brown rice"));
        assertThat(pack.nutritionSources().get("rice").ingredient().name()).isEqualTo("Cooked brown rice");
        assertThat(pack.weeklyMealPlan()).isEqualTo(json.readValue(legacy,PlanningPackage.class).weeklyMealPlan());
        assertThat(pack.planVersion()).isEqualTo(p.version);
        assertThat(mapper.delivery(p.planId,p.version).payload()).isEqualTo(legacy);
    }
    @Test void aNonMondayStartGeneratesAndPublishesSevenConsecutiveDates()throws Exception {
        var a=assessment(List.of());var start=LocalDate.parse("2026-10-14"); // Tomorrow in the member's timezone, a Wednesday.
        var request=new Generate(UUID.fromString(a.assessmentId),start,true);
        var response=request("POST","/plans",request,token);assertThat(response.statusCode()).isEqualTo(200);
        var p=json.readValue(response.body(),Plan.class);assertThat(p.state).isEqualTo(State.DRAFT);
        assertThat(p.weekStart).isEqualTo(start);assertThat(p.weekEnd).isEqualTo(start.plusDays(6));
        assertThat(p.meals).hasSize(21);assertThat(p.meals.stream().map(Meal::date).distinct().toList()).containsExactlyElementsOf(start.datesUntil(start.plusDays(7)).toList());
        assertThat(service.generate(MEMBER,request).planId).isEqualTo(p.planId);
        publish(p);var pack=service.packageFor(MEMBER,p.planId);
        assertThat(pack.weekStart()).isEqualTo(start);assertThat(pack.weekEnd()).isEqualTo(start.plusDays(6));
        assertThat(pack.dailyNutrition().stream().map(DailyNutrition::date).toList()).containsExactlyElementsOf(start.datesUntil(start.plusDays(7)).toList());
    }
    @Test void arbitraryStartDatesRespectLocalTodayAndTwelveWeekLimits() {
        var a=assessment(List.of());when(clock.instant()).thenReturn(Instant.parse("2026-10-12T14:30:00Z")); // October 13 in Sydney.
        var today=LocalDate.parse("2026-10-13");var id=UUID.fromString(a.assessmentId);
        assertThatThrownBy(()->service.generate(MEMBER,new Generate(id,today.minusDays(1),true))).isInstanceOf(AgentException.class);
        assertThatThrownBy(()->service.generate(MEMBER,new Generate(id,today.plusWeeks(12).plusDays(1),true))).isInstanceOf(AgentException.class);
        verifyNoInteractions(model,nutrition);
        assertThat(service.generate(MEMBER,new Generate(id,today,true)).weekStart).isEqualTo(today);
        assertThat(service.generate(MEMBER,new Generate(id,today.plusWeeks(12),true)).weekStart).isEqualTo(today.plusWeeks(12));
    }
    @Test void partialAssessmentFeedsPlanningAndUpdatedEvidenceInvalidatesPublishedPlan(){
        var a=assessment(List.of());a.incompleteRecords=true;a.partialDataAccepted=true;a.observedDays=2;a.intakeCount=2;a.riskStatus=Risk.LIMITED_DATA;
        healthMapper.updateAssessment(a,json.writeValueAsString(a));
        var p=publish(generate(a));assertThat(p.meals).hasSize(21);assertThat(p.priorities).anyMatch(s->s.contains("partial meal records"));
        verify(model).propose(argThat(i->i.incompleteRecords()&&i.observedDays()==2&&i.intakeCount()==2),anyString(),anyString(),anyMap(),any(),anyList(),anyList());
        a.revision++;a.intakeCount=3;healthMapper.updateAssessment(a,json.writeValueAsString(a));
        assertThat(service.get(MEMBER,p.planId).state).isEqualTo(State.STALE);
        assertThat(mapper.delivery(p.planId,1).state()).isEqualTo("CANCELLED");
        var renewed=generate(a);assertThat(renewed.planId).isEqualTo(p.planId);assertThat(renewed.version).isEqualTo(2);
        assertThat(renewed.state).isEqualTo(State.DRAFT);assertThat(renewed.assessmentRevision).isEqualTo(a.revision);
        assertThat(renewed.audit).anyMatch(e->e.action().equals("ASSESSMENT_UPDATED"));
        assertThat(generate(a).version).isEqualTo(2);publish(renewed);
        assertThat(service.packageFor(MEMBER,p.planId).assessmentRevision()).isEqualTo(a.revision);
        assertThat(mapper.delivery(p.planId,1).state()).isEqualTo("CANCELLED");
    }
    @Test void nutritionLoadingUsesAtMostFourConcurrentLookups(){
        var firstBatch=new CountDownLatch(4);var active=new java.util.concurrent.atomic.AtomicInteger();var maximum=new java.util.concurrent.atomic.AtomicInteger();
        when(nutrition.lookup(any())).thenAnswer(call->{
            int running=active.incrementAndGet();maximum.accumulateAndGet(running,Math::max);firstBatch.countDown();
            try{assertThat(firstBatch.await(5,TimeUnit.SECONDS)).isTrue();FoodInput f=call.getArgument(0);return match(f.searchTerm(),"0.95");}
            finally{active.decrementAndGet();}
        });
        var p=generate(assessment(List.of()));assertThat(p.state).isEqualTo(State.DRAFT);assertThat(maximum.get()).isEqualTo(4);
    }
    @Test void duplicateSlotIsRepairedWithoutRegeneratingOtherMealsOrReportingFalseDailyDeficits(){
        var week=new ArrayList<>(candidate("rice").meals());week.set(11,meal(3,Slot.LUNCH,"rice"));
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList()))
            .thenReturn(new Candidate(week),new Candidate(List.of(meal(3,Slot.LUNCH,"rice"),meal(3,Slot.DINNER,"rice"))));
        var p=generate(assessment(List.of()));assertThat(p.state).isEqualTo(State.DRAFT);assertThat(p.attempts).isEqualTo(2);
        assertThat(p.candidate.meals()).isEqualTo(candidate("rice").meals());
        verify(model).propose(any(),anyString(),anyString(),anyMap(),any(),eq(List.of("d3-LUNCH","d3-DINNER")),argThat(f->f.stream().anyMatch(x->x.contains("d3-LUNCH: duplicate"))&&f.stream().anyMatch(x->x.contains("d3-DINNER: missing"))&&f.stream().noneMatch(x->x.startsWith("Day 4:"))));
    }
    @Test void nutrientRepairRequestsOnlyTheFailingDayAndKeepsOtherDays(){
        var week=new ArrayList<>(candidate("rice").meals());
        for(int i=9;i<12;i++)week.set(i,new CandidateMeal(3,Slot.values()[i-9],List.of(new Portion("rice",n("1")))));
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList()))
            .thenReturn(new Candidate(week),new Candidate(List.of(meal(3,Slot.BREAKFAST,"rice"),meal(3,Slot.LUNCH,"rice"),meal(3,Slot.DINNER,"rice"))));
        var p=generate(assessment(List.of()));assertThat(p.state).isEqualTo(State.DRAFT);assertThat(p.attempts).isEqualTo(2);
        assertThat(p.candidate.meals()).isEqualTo(candidate("rice").meals());
        verify(model).propose(any(),anyString(),anyString(),anyMap(),any(),eq(List.of("d3-BREAKFAST","d3-LUNCH","d3-DINNER")),argThat(f->f.stream().anyMatch(x->x.startsWith("Day 4:"))));
    }
    @Test void tooManyIngredientsNamesExactMealAndSkipsMisleadingNutrientChecks(){
        var p=new Plan();p.candidate=candidate("rice");p.weekStart=WEEK;p.constraints=new Constraints(List.of(),List.of(),List.of(),targets(),List.of());
        var meals=new ArrayList<>(p.candidate.meals());var nine=new ArrayList<Portion>();for(int i=0;i<9;i++)nine.add(new Portion("rice",n("20")));
        meals.set(11,new CandidateMeal(3,Slot.DINNER,nine));p.candidate=new Candidate(meals);engine.validate(p);
        assertThat(p.issues).containsExactly("d3-DINNER: 9 ingredients; maximum is 8. Remove extra ingredients and keep the dish name consistent.");
        assertThat(p.dailyNutrition).isEmpty();assertThat(engine.repairLines(p)).containsExactly("d3-DINNER");assertThat(engine.tune(p)).isNull();
    }
    @Test void arithmeticMissesAreRescaledBeforeAnotherModelAttempt(){
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(candidate("rice","150"));var p=generate(assessment(List.of()));
        assertThat(p.state).isEqualTo(State.DRAFT);assertThat(p.attempts).isEqualTo(1);assertThat(p.audit).anyMatch(e->e.action().equals("PORTIONS_ADJUSTED"));
        assertThat(p.meals).allSatisfy(m->assertThat(m.ingredients()).singleElement().satisfies(i->assertThat(i.ingredientId()).isEqualTo("rice")));
        assertThat(p.dailyNutrition).allSatisfy(d->assertThat(d.nutrients().get("1008").amount()).isBetween(n("550"),n("650")));verify(model,times(1)).propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList());
    }
    @Test void invalidIngredientFeedbackNamesTheProblem(){
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(candidate("brown_rice"));var p=generate(assessment(List.of()));
        assertThat(p.issues).anyMatch(i->i.contains("unknown ingredient ID brown_rice"));
        verify(model,atLeastOnce()).propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),argThat(feedback->feedback.stream().anyMatch(i->i.contains("unknown ingredient ID brown_rice"))));
    }
    @Test void replanRequestsCarryPlanAndAttemptCount(){
        var p=publish(generate(assessment(List.of())));var base=replan(p.version,"d0-BREAKFAST","rice");
        assertThatThrownBy(()->service.replan(MEMBER,p.planId,new Replan(UUID.randomUUID(),p.version,base.reason(),base.evidence(),base.menuLineIds(),base.unavailableIngredientIds(),true,UUID.randomUUID(),1))).isInstanceOf(AgentException.class).hasMessageContaining("different plan");
        assertThatThrownBy(()->service.replan(MEMBER,p.planId,new Replan(UUID.randomUUID(),p.version,base.reason(),base.evidence(),base.menuLineIds(),base.unavailableIngredientIds(),true,UUID.fromString(p.planId),2))).isInstanceOf(AgentException.class).hasMessageContaining("attempt");
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(new Candidate(List.of(new CandidateMeal(0,Slot.BREAKFAST,List.of(new Portion("potato",n("200")))))));
        var r=service.replan(MEMBER,p.planId,new Replan(UUID.randomUUID(),p.version,base.reason(),base.evidence(),base.menuLineIds(),base.unavailableIngredientIds(),true,UUID.fromString(p.planId),1));
        assertThat(r.replans).singleElement().satisfies(a->assertThat(a.attempt()).isEqualTo(1));
    }
    @Test void partialReplanRescalesOnlyAffectedLines(){
        var p=publish(generate(assessment(List.of())));
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(new Candidate(List.of(new CandidateMeal(0,Slot.BREAKFAST,List.of(new Portion("potato",n("100")))))));
        var r=service.replan(MEMBER,p.planId,replan(p.version,"d0-BREAKFAST","rice"));assertThat(r.state).isEqualTo(State.DRAFT);
        assertThat(r.meals).allSatisfy(m->{var i=m.ingredients().get(0);if(m.menuLineId().equals("d0-BREAKFAST")){assertThat(i.ingredientId()).isEqualTo("potato");assertThat(i.grams()).isGreaterThan(n("150"));}else{assertThat(i.ingredientId()).isEqualTo("rice");assertThat(i.grams()).isEqualByComparingTo("200");}});
    }
    @Test void apiRequiresJwtOwnershipConsentAndValidWeek()throws Exception{
        var a=assessment(List.of());assertThat(request("POST","/plans",new Generate(UUID.fromString(a.assessmentId),WEEK,false),token).statusCode()).isEqualTo(400);
        assertThat(request("GET","/plans",null,null).statusCode()).isEqualTo(401);
        assertThat(request("POST","/plans",new Generate(UUID.fromString(a.assessmentId),NOW.atZone(ZoneId.of(a.timezone)).toLocalDate().minusDays(1),true),token).statusCode()).isEqualTo(400);
        var p=generate(a);assertThat(request("GET","/plans/"+p.planId,null,otherToken).statusCode()).isEqualTo(404);
        assertThat(request("POST","/plans/"+p.planId+"/publish",new Publish(p.version,true),otherToken).statusCode()).isEqualTo(404);
        assertThat(request("GET","/expert/queue",null,expertToken).statusCode()).isEqualTo(404);
        assertThat(request("GET","/plans?page=0",null,token).statusCode()).isEqualTo(400);
        assertThat(request("POST","/plans/"+p.planId+"/publish",new Publish(99,true),token).statusCode()).isEqualTo(409);
    }
    @Test void blockedAssessmentNeverCallsProviders(){var a=assessment(List.of());a.planningClearance=Clearance.NOT_READY;healthMapper.updateAssessment(a,json.writeValueAsString(a));assertThatThrownBy(()->generate(a)).isInstanceOf(AgentException.class);verifyNoInteractions(model,nutrition);}
    @Test void invalidPlanPausesAfterBoundedRevisionsAndRetriesWithFreshBudget(){
        var a=assessment(List.of("milk"));when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(candidate("yogurt"));var p=generate(a);
        assertThat(p.state).isEqualTo(State.DEFERRED);assertThat(p.attempts).isEqualTo(2);assertThat(p.issues).anyMatch(i->i.contains("prohibited ingredient"));assertThat(p.issues).anyMatch(i->i.contains("repeated the same invalid"));
        assertThatThrownBy(()->publish(p)).isInstanceOf(AgentException.class);
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(candidate("rice"));
        var fixed=service.retry(MEMBER,p.planId,new Version(1));
        assertThat(fixed.state).isEqualTo(State.DRAFT);assertThat(fixed.attempts).isEqualTo(1);assertThat(publish(fixed).state).isEqualTo(State.PUBLISHED);
    }
    Assessment withConditions(List<String> conditions){var a=assessment(List.of());a.healthConstraintSet=new Constraints(List.of(),List.of(),conditions,targets(),List.of());healthMapper.updateAssessment(a,json.writeValueAsString(a));return a;}
    // Agent 2 already routes unusual conditions to an expert, so a cleared assessment plans without a second review.
    @Test void clearedConditionsGoStraightToDraft(){var p=generate(withConditions(List.of("Hypertension","Coeliac disease")));assertThat(p.state).isEqualTo(State.DRAFT);assertThat(p.sourceNotes).isEmpty();assertThat(publish(p).state).isEqualTo(State.PUBLISHED);}
    @Test void approximateMatchesAreSourceNotesForTheMember(){
        when(nutrition.lookup(any())).thenReturn(match("Approximate description","0.60"));var p=generate(assessment(List.of()));
        assertThat(p.state).isEqualTo(State.DRAFT);assertThat(p.sourceNotes).singleElement().satisfies(n->assertThat(n).contains("Approximate description"));assertThat(publish(p).state).isEqualTo(State.PUBLISHED);
    }
    @Test void dishNamesBecomeMealTitlesAndMealStylesReachTheModel(){
        var base=input(0,List.of());var styled=new ProfileInput(0,base.timezone(),base.goals(),base.allergies(),base.restrictions(),base.conditions(),base.preferences(),base.targetSource(),base.targets(),false,null,false,List.of("Asian","High-protein / fitness"));
        var meals=new ArrayList<CandidateMeal>();for(var m:candidate("rice").meals())meals.add(new CandidateMeal(m.day(),m.slot(),m.ingredients(),"Steamed brown rice bowl"));
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(new Candidate(meals));
        var p=generate(assessment(styled));assertThat(p.state).isEqualTo(State.DRAFT);assertThat(p.meals).allSatisfy(m->assertThat(m.title()).isEqualTo("Steamed brown rice bowl"));
        verify(model).propose(any(),anyString(),contains("Preferred meal styles: Asian, High-protein / fitness"),anyMap(),any(),anyList(),anyList());
    }
    @Test void pescatarianAllowsSeafoodAndSeasoningsHaveSmallLimits(){
        var salmon=catalog.get("salmon");
        assertThat(catalog.allowed(salmon,new Constraints(List.of(),List.of("pescatarian"),List.of(),targets(),List.of()))).isTrue();
        assertThat(catalog.allowed(salmon,new Constraints(List.of(),List.of("vegetarian"),List.of(),targets(),List.of()))).isFalse();
        assertThat(catalog.allowed(catalog.get("prawns"),new Constraints(List.of("no seafood"),List.of(),List.of(),targets(),List.of()))).isFalse();
        var meals=new ArrayList<>(candidate("rice").meals());meals.set(0,new CandidateMeal(0,Slot.BREAKFAST,List.of(new Portion("rice",n("200")),new Portion("soy_sauce",n("50")))));
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(new Candidate(meals));
        // The deterministic tuner pulls the seasoning back inside its 20 g ceiling instead of spending a model attempt.
        var p=generate(assessment(List.of()));assertThat(p.state).isEqualTo(State.DRAFT);
        assertThat(p.candidate.meals().get(0).ingredients()).filteredOn(i->i.ingredientId().equals("soy_sauce")).singleElement().satisfies(i->assertThat(i.grams()).isLessThanOrEqualTo(n("20")));
    }
    @Test void commonAllergyPhrasingIsRecognised(){
        assertThat(catalog.unrecognised(List.of("no seafood","Peanut-free","dairy allergy","Unmapped allergy"),List.of("pescatarian","keto","apple","no apples","milk"))).containsExactly("Unmapped allergy","keto","milk");
        assertThat(catalog.allowed(catalog.get("apple"),new Constraints(List.of(),List.of("apple"),List.of(),targets(),List.of()))).isFalse();
        assertThat(catalog.compositionProblem("tomato",List.of("Tomatoes (68%)","Sugar","Vinegar"))).contains("68%");
        assertThat(catalog.compositionProblem("tofu",List.of("Water","Soybeans (40%)","Calcium sulphate"))).isNull();
        assertThat(catalog.allowed(catalog.get("yogurt"),new Constraints(List.of("no dairy"),List.of(),List.of(),targets(),List.of()))).isFalse();
    }
    @Test void missingNutritionIsNotZeroAndUpperLimitsAreEnforced(){
        var a=assessment(List.of());when(nutrition.lookup(any())).thenReturn(new NutritionMatch(123,"Partial fixture",n("0.95"),Map.of("1008",new Nutrient(n("1000"),"kcal")),null));var p=generate(a);
        assertThat(p.state).isEqualTo(State.DEFERRED);assertThat(p.issues).anyMatch(i->i.contains("missing or incompatible"));assertThat(p.issues).anyMatch(i->i.contains("calculated 6000"));
    }
    @Test void providerFailurePausesAndOnlyPausedPlansRetry(){
        var a=assessment(List.of());when(nutrition.lookup(any())).thenThrow(AgentException.provider("NUTRITION_NOT_CONFIGURED","Configure nutrition provider"));var p=generate(a);assertThat(p.state).isEqualTo(State.DEFERRED);
        p=service.retry(MEMBER,p.planId,new Version(1));assertThat(p.state).isEqualTo(State.DEFERRED);assertThat(p.attempts).isEqualTo(1);
        doAnswer(call->{FoodInput f=call.getArgument(0);return match(f==null?"Fixture":f.searchTerm(),"0.95");}).when(nutrition).lookup(any());
        p=service.retry(MEMBER,p.planId,new Version(1));assertThat(p.state).isEqualTo(State.DRAFT);String id=p.planId;assertThatThrownBy(()->service.retry(MEMBER,id,new Version(1))).isInstanceOf(AgentException.class);
    }
    @Test void profileChangeCancelsPublishedPackage(){var p=publish(generate(assessment(List.of())));health.saveProfile(MEMBER,input(1,List.of()));assertThat(service.get(MEMBER,p.planId).state).isEqualTo(State.STALE);assertThat(mapper.delivery(p.planId,1).state()).isEqualTo("CANCELLED");assertThatThrownBy(()->service.packageFor(MEMBER,p.planId)).isInstanceOf(AgentException.class);}
    @Test void revokedAssessmentAndExpiredWeekInvalidatePlan(){
        var a=assessment(List.of());var p=generate(a);a.planningClearance=Clearance.REFER_TO_HEALTHCARE;a.revision++;healthMapper.updateAssessment(a,json.writeValueAsString(a));assertThat(service.get(MEMBER,p.planId).state).isEqualTo(State.STALE);
        a.planningClearance=Clearance.CLEARED;healthMapper.updateAssessment(a,json.writeValueAsString(a));var later=service.generate(MEMBER,new Generate(UUID.fromString(a.assessmentId),WEEK.plusWeeks(1),true));when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(30)));assertThat(service.get(MEMBER,later.planId).state).isEqualTo(State.STALE);
    }
    @Test void replanChangesOnlyAffectedMealAndDeduplicatesRequest(){
        var p=publish(generate(assessment(List.of())));var request=replan(1,"d0-BREAKFAST","rice");when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(new Candidate(List.of(meal(0,Slot.BREAKFAST,"potato"))));
        var updated=service.replan(MEMBER,p.planId,request);assertThat(updated.state).isEqualTo(State.DRAFT);assertThat(updated.version).isEqualTo(2);assertThat(updated.candidate.meals().subList(1,21)).isEqualTo(p.candidate.meals().subList(1,21));assertThat(updated.candidate.meals().get(0).ingredients().get(0).ingredientId()).isEqualTo("potato");
        assertThat(mapper.delivery(p.planId,1).state()).isEqualTo("CANCELLED");assertThat(service.replan(MEMBER,p.planId,request).version).isEqualTo(2);
        assertThatThrownBy(()->service.replan(MEMBER,p.planId,new Replan(request.requestId(),1,ReplanReason.PRICE,"Different evidence",request.menuLineIds(),request.unavailableIngredientIds(),true))).isInstanceOf(AgentException.class);
        publish(updated);assertThat(service.packageFor(MEMBER,p.planId).planVersion()).isEqualTo(2);
    }
    @Test void replanTakesOnlyAffectedLinesWhenTheModelEchoesTheWholeWeek(){
        var p=publish(generate(assessment(List.of())));List<CandidateMeal> week=new ArrayList<>(p.candidate.meals());
        week.set(0,meal(0,Slot.BREAKFAST,"potato"));week.set(1,meal(0,Slot.LUNCH,"potato"));
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(new Candidate(week));
        var updated=service.replan(MEMBER,p.planId,replan(1,"d0-BREAKFAST","rice"));
        assertThat(updated.state).isEqualTo(State.DRAFT);assertThat(updated.candidate.meals().get(0).ingredients().get(0).ingredientId()).isEqualTo("potato");
        // The echoed change to an unaffected line is ignored.
        assertThat(updated.candidate.meals().subList(1,21)).isEqualTo(p.candidate.meals().subList(1,21));
    }
    @Test void invalidPartialResponseDoesNotLoseOriginalCandidate(){
        var p=publish(generate(assessment(List.of())));
        // The response leaves out the affected line, so it cannot be merged.
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(new Candidate(List.of(meal(1,Slot.LUNCH,"potato"))));
        var next=service.replan(MEMBER,p.planId,replan(1,"d0-BREAKFAST","rice"));assertThat(next.state).isEqualTo(State.DEFERRED);assertThat(next.candidate).isEqualTo(p.candidate);
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(new Candidate(List.of(meal(0,Slot.BREAKFAST,"potato"))));assertThat(service.retry(MEMBER,p.planId,new Version(2)).state).isEqualTo(State.DRAFT);
    }
    @Test void replanLimitIsRefusedAndKeepsThePublishedPlan(){
        var p=publish(generate(assessment(List.of())));String[] replacements={"potato","lentils"};String previous="rice";
        for(String replacement:replacements){when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(new Candidate(List.of(meal(0,Slot.BREAKFAST,replacement))));p=publish(service.replan(MEMBER,p.planId,replan(p.version,"d0-BREAKFAST",previous)));previous=replacement;}
        var published=p;var last=replan(p.version,"d0-BREAKFAST",previous);
        assertThatThrownBy(()->service.replan(MEMBER,published.planId,last)).isInstanceOfSatisfying(AgentException.class,e->assertThat(e.code()).isEqualTo("REPLAN_LIMIT_REACHED"));
        var after=service.get(MEMBER,p.planId);assertThat(after.state).isEqualTo(State.PUBLISHED);assertThat(after.version).isEqualTo(p.version);assertThat(service.packageFor(MEMBER,p.planId).planVersion()).isEqualTo(p.version);
    }
    @Test void profileChangeDuringModelCallDiscardsLateResult(){
        var a=assessment(List.of());when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenAnswer(call->{health.saveProfile(MEMBER,input(1,List.of()));return candidate("rice");});var p=generate(a);assertThat(p.state).isEqualTo(State.STALE);assertThat(p.meals).isEmpty();
    }
    @Test void concurrentGenerationUsesSingleLease()throws Exception{
        var a=assessment(List.of());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenAnswer(call->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return candidate("rice");});var executor=Executors.newSingleThreadExecutor();
        try{var pending=executor.submit(()->generate(a));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();var duplicate=generate(a);assertThat(duplicate.state).isEqualTo(State.GENERATING);release.countDown();assertThat(pending.get(10,TimeUnit.SECONDS).planId).isEqualTo(duplicate.planId);verify(model,times(1)).propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList());}finally{release.countDown();executor.shutdownNow();}
    }
    @Test void legacyPlansAwaitingExpertReviewAreRecheckedOnLoad(){
        var p=generate(assessment(List.of()));var doc=(ObjectNode)json.readTree(json.writeValueAsString(p));
        doc.put("state","REVIEW_REQUIRED");doc.putArray("reviewReasons").add("An expert must interpret this allergy: x");doc.putArray("expertDecisions");
        jdbc.update("UPDATE nutrition_plan SET state='REVIEW_REQUIRED',document=? WHERE plan_id=?",doc.toString(),p.planId);
        var migrated=service.get(MEMBER,p.planId);assertThat(migrated.state).isEqualTo(State.DRAFT);assertThat(migrated.audit).anyMatch(e->e.action().equals("EXPERT_REVIEW_REMOVED"));assertThat(publish(migrated).state).isEqualTo(State.PUBLISHED);
    }
    @Test void outboxRetriesUsesVersionedIdempotencyAndStopsAfterSuccess()throws Exception{
        var p=publish(generate(assessment(List.of())));var count=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/market",exchange->{assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo(p.planId+":1");exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(count.incrementAndGet()==1?503:204,-1);exchange.close();});server.start();
        try{var policy=new PlanningPolicy("planning-v1","qwen-plus",Duration.ofSeconds(5),3,2,"http://127.0.0.1:"+server.getAddress().getPort()+"/market","",Duration.ofSeconds(5),3,0.2);var delivery=new PlanningDelivery(mapper,service,policy);delivery.deliverPending();assertThat(mapper.delivery(p.planId,1).state()).isEqualTo("QUEUED");delivery.deliverPending();delivery.deliverPending();assertThat(count.get()).isEqualTo(2);assertThat(mapper.delivery(p.planId,1).state()).isEqualTo("DELIVERED");}finally{server.stop(0);}
    }
    @Test void deliveryInvalidatesStalePackageBeforeSending()throws Exception{
        var p=publish(generate(assessment(List.of())));health.saveProfile(MEMBER,input(1,List.of()));var policy=new PlanningPolicy("planning-v1","qwen-plus",Duration.ofSeconds(1),3,2,"http://127.0.0.1:1/unused","",Duration.ofSeconds(1),3,0.2);new PlanningDelivery(mapper,service,policy).deliverPending();assertThat(mapper.delivery(p.planId,1).state()).isEqualTo("CANCELLED");assertThat(mapper.delivery(p.planId,1).attempts()).isZero();
    }
}
