package com.nutrimarket.nutrimarket.health;

import com.nutrimarket.nutrimarket.dao.auth.UserMapper;
import com.nutrimarket.nutrimarket.dao.dietary.IntakeOutboxMapper;
import com.nutrimarket.nutrimarket.dao.dietary.IntakeRecordMapper;
import com.nutrimarket.nutrimarket.dao.health.HealthMapper;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.integration.http.ProviderHttp;
import com.nutrimarket.nutrimarket.model.auth.AuthModels;
import com.nutrimarket.nutrimarket.service.auth.JwtTokenService;
import com.nutrimarket.nutrimarket.service.dietary.DietaryAnalysisService;
import com.nutrimarket.nutrimarket.service.dietary.IntakeDelivery;
import com.nutrimarket.nutrimarket.service.health.HealthExplanation;
import com.nutrimarket.nutrimarket.service.health.HealthInbox;
import com.nutrimarket.nutrimarket.service.health.HealthScheduler;
import com.nutrimarket.nutrimarket.service.health.HealthService;

import com.nutrimarket.nutrimarket.config.AgentConfiguration;
import com.nutrimarket.nutrimarket.config.HealthProfileDefaults;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.nutrimarket.nutrimarket.model.health.HealthModels.*;
import static com.nutrimarket.nutrimarket.model.dietary.DietaryModels.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.import=","debug=false","server.address=127.0.0.1",
    "spring.datasource.url=jdbc:h2:mem:health-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.sql.init.schema-locations=classpath:schema-h2.sql",
    "nutrimarket.integrations.vision.api-key=local-test-fixture","nutrimarket.health.expert-usernames[0]=reviewer","nutrimarket.dietary.delivery-delay-ms=3600000"})
class HealthIntegrationTests {
    static final String MEMBER="00000000-0000-0000-0000-000000000201",OTHER="00000000-0000-0000-0000-000000000202",EXPERT="00000000-0000-0000-0000-000000000203";
    static final Instant NOW=Instant.parse("2026-10-13T00:00:00Z");
    static final LocalDate WEEK=LocalDate.parse("2026-10-05");
    @Autowired DietaryAnalysisService dietary;
    @Autowired HealthService service;@Autowired HealthInbox inbox;@Autowired HealthMapper health;
    @Autowired HealthExplanation explanation;@Autowired IntakeDelivery delivery;
    @Autowired UserMapper users;@Autowired JwtTokenService tokens;
    @Autowired IntakeRecordMapper records;@Autowired IntakeOutboxMapper outbox;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    @Autowired HealthProfileDefaults profileDefaults;
    @MockitoBean Clock clock;@MockitoBean ProviderHttp provider;
    @Value("${local.server.port}") int port;
    String token,otherToken,expertToken;
    @BeforeEach void setup(){
        when(clock.instant()).thenReturn(NOW);
        for(String table:List.of("weekly_health_assessment","health_intake","member_health_profile","dietary_outbox","dietary_record","member_account"))jdbc.update("DELETE FROM "+table);
        token=user(MEMBER,"member");otherToken=user(OTHER,"other");expertToken=user(EXPERT,"reviewer");
    }
    String user(String id,String name){var user=new AuthModels.UserAccount(id,name,"unused",true,NOW);users.insert(user);return tokens.issue(user).accessToken();}
    Map<String,Target> targets(){return Map.of("1008",target("1500","2100","kcal"),"1003",target("45","80","g"),"1004",target("30","70","g"),"1005",target("180","300","g"));}
    Target target(String low,String high,String unit){return new Target(new BigDecimal(low),new BigDecimal(high),unit);}
    ProfileInput input(long version,List<String> conditions){return new ProfileInput(version,"Australia/Sydney","Track meals",List.of("peanut"),List.of("vegetarian"),conditions,"Mild flavours","Targets supplied for testing",targets(),false);}
    Profile profile(){return service.saveProfile(MEMBER,input(0,List.of()));}
    UUID meal(String member,Instant at,long version,boolean missing,String calories){
        UUID id=UUID.randomUUID();Map<String,Nutrient> nutrients=new TreeMap<>(Map.of("1008",new Nutrient(new BigDecimal(calories),"kcal"),"1003",new Nutrient(new BigDecimal("20"),"g"),"1004",new Nutrient(new BigDecimal("15"),"g"),"1005",new Nutrient(new BigDecimal("80"),"g")));
        if(missing)nutrients.remove("1003");
        var r=new IntakeRecord();r.recordId=id;r.memberId=member;r.profileVersion=version;r.status=Status.VALIDATED;r.mealTime=at;r.createdAt=at;r.analyzedAt=at;r.confirmedAt=at;r.policyVersion="dietary-v1";r.confidence=BigDecimal.ONE;r.nutrients=nutrients;
        records.insert(new IntakeRecordMapper.RecordRow(id.toString(),member,"fingerprint",member,json.writeValueAsString(r),null,"image/png","VALIDATED",at,null));
        outbox.insert(id.toString(),json.writeValueAsString(ValidatedDietaryIntakeRecord.from(r,at)));return id;
    }
    void week(boolean missing,String calories){for(int d=0;d<7;d++)for(int m=0;m<3;m++)meal(MEMBER,WEEK.plusDays(d).atTime(8+m*5,0).atZone(ZoneId.of("Australia/Sydney")).toInstant(),1,missing&&d==0&&m==0,calories);}
    HttpResponse<String> request(String method,String path,Object body,String bearer)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/health"+path));
        if(bearer!=null)b.header("Authorization","Bearer "+bearer);
        b.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void profileRequiredAndInputValidationAndOptimisticVersions()throws Exception{
        assertThat(request("GET","/profile",null,token).statusCode()).isEqualTo(404);
        assertThat(request("POST","/assessments",Map.of("periodStart",WEEK),token).statusCode()).isEqualTo(404);
        assertThat(request("PUT","/profile",input(0,List.of()),token).statusCode()).isEqualTo(200);
        assertThat(request("PUT","/profile",input(0,List.of()),token).statusCode()).isEqualTo(409);
        assertThat(request("PUT","/profile",Map.of(),token).statusCode()).isEqualTo(400);
        assertThat(request("GET","/profile",null,null).statusCode()).isEqualTo(401);
    }
    Map<String,Object> profileWithoutTargets(long version) {
        return new HashMap<>(Map.of("expectedVersion",version,"timezone","Australia/Sydney",
                "goals","Build regular eating habits","allergies",List.of(),"restrictions",List.of(),
                "conditions",List.of(),"preferences","Simple meals","aiSummaryConsent",false));
    }
    @Test void newProfileUsesSystemTargetsAndCanProducePlanningInput()throws Exception {
        assertThat(request("PUT","/profile",profileWithoutTargets(0),token).statusCode()).isEqualTo(200);
        var p=service.profile(MEMBER);
        assertThat(p.information().targets()).isEqualTo(profileDefaults.targets());
        assertThat(p.information().targetSource()).isEqualTo("NutriMarket application defaults");
        assertThat(p.information().targets().get("1008").minimum()).isEqualByComparingTo("1800");
        week(false,"600");var a=service.generate(MEMBER,WEEK);
        assertThat(a.status).isEqualTo(AssessmentStatus.COMPLETED);
        assertThat(service.planningInput(MEMBER,a.assessmentId).healthConstraintSet().dailyTargets()).isEqualTo(p.information().targets());
    }
    void aiTargets(String body){when(provider.json(anyString(),anyString(),anyString(),any(),any(),eq("HEALTH_TARGETS"))).thenReturn(json.readTree("{\"choices\":[{\"message\":{\"content\":"+json.writeValueAsString(body)+"}}]}"));}
    static final String MUSCLE="{\"targets\":{\"energy\":{\"minimum\":2200,\"maximum\":2600},\"protein\":{\"minimum\":110,\"maximum\":150},\"fat\":{\"minimum\":60,\"maximum\":85},\"carbohydrate\":{\"minimum\":250,\"maximum\":330}},\"rationale\":\"Muscle gain needs a modest energy surplus and higher protein.\"}";
    @Test void consentedProfilesGetAiTargetsFromGoalsAndReuseThemUntilGoalsChange()throws Exception {
        aiTargets(MUSCLE);var body=profileWithoutTargets(0);body.put("goals","Build muscle");body.put("aiSummaryConsent",true);
        assertThat(request("PUT","/profile",body,token).statusCode()).isEqualTo(200);var info=service.profile(MEMBER).information();
        assertThat(info.targets().get("1003").minimum()).isEqualByComparingTo("110");assertThat(info.targets().get("1008").unit()).isEqualTo("kcal");
        assertThat(info.targetSource()).startsWith("Generated by Qwen");assertThat(info.targetRationale()).contains("higher protein");
        var sent=org.mockito.ArgumentCaptor.forClass(Object.class);verify(provider).json(anyString(),anyString(),anyString(),any(),sent.capture(),eq("HEALTH_TARGETS"));
        assertThat(json.writeValueAsString(sent.getValue())).contains("Build muscle").doesNotContain(MEMBER,"Simple meals");
        week(false,"600");var a=service.generate(MEMBER,WEEK);assertThat(a.targetRationale).isEqualTo(info.targetRationale());assertThat(a.healthConstraintSet.dailyTargets()).isEqualTo(info.targets());
        body.put("expectedVersion",1);body.put("preferences","Spicy food");request("PUT","/profile",body,token);
        assertThat(service.profile(MEMBER).information().targets()).isEqualTo(info.targets());verify(provider,times(1)).json(anyString(),anyString(),anyString(),any(),any(),eq("HEALTH_TARGETS"));
        body.put("expectedVersion",2);body.put("goals","Lose weight gradually");request("PUT","/profile",body,token);
        verify(provider,times(2)).json(anyString(),anyString(),anyString(),any(),any(),eq("HEALTH_TARGETS"));
        body.put("expectedVersion",3);body.put("goals","Eat more vegetables");body.put("aiSummaryConsent",false);request("PUT","/profile",body,token);
        var withdrawn=service.profile(MEMBER).information();assertThat(withdrawn.targets()).isEqualTo(profileDefaults.targets());assertThat(withdrawn.targetRationale()).isNull();
        verify(provider,times(2)).json(anyString(),anyString(),anyString(),any(),any(),eq("HEALTH_TARGETS"));
    }
    static Map<String,Object> body(String height,String weight,int birthYear,String sex,String activity){
        var b=new HashMap<String,Object>();b.put("heightCm",new BigDecimal(height));b.put("weightKg",new BigDecimal(weight));b.put("birthYear",birthYear);b.put("sex",sex);b.put("activityLevel",activity);return b;
    }
    @Test void bodyMeasurementsGiveCalculatedTargetsWithoutAiConsent()throws Exception {
        // 180 cm, 80 kg, 30 years, male: resting 10×80 + 6.25×180 − 5×30 + 5 = 1780 kcal; × 1.55 moderate activity = 2759 kcal.
        var request=profileWithoutTargets(0);request.put("body",body("180","80",1996,"MALE","MODERATE"));
        assertThat(request("PUT","/profile",request,token).statusCode()).isEqualTo(200);var info=service.profile(MEMBER).information();
        assertThat(info.targets().get("1008")).isEqualTo(target("2480","3030","kcal"));assertThat(info.targets().get("1003")).isEqualTo(target("80","128","g"));
        assertThat(info.targets().get("1004")).isEqualTo(target("77","107","g"));assertThat(info.targets().get("1005")).isEqualTo(target("310","414","g"));
        assertThat(info.targetSource()).startsWith("Calculated from your height, weight, age, sex and activity level");assertThat(info.targetRationale()).contains("2759","1780","1.55");
        assertThat(info.body().weightKg()).isEqualByComparingTo("80");verifyNoInteractions(provider);
        // A weight-loss goal lowers energy by 15%; preference edits keep the targets; new measurements recalculate them.
        request.put("expectedVersion",1);request.put("goals","Lose weight gradually");request("PUT","/profile",request,token);
        assertThat(service.profile(MEMBER).information().targets().get("1008")).isEqualTo(target("2110","2580","kcal"));
        request.put("expectedVersion",2);request.put("preferences","Spicy food");request("PUT","/profile",request,token);
        assertThat(service.profile(MEMBER).information().targets().get("1008")).isEqualTo(target("2110","2580","kcal"));
        request.put("expectedVersion",3);request.put("body",body("180","70",1996,"MALE","MODERATE"));request("PUT","/profile",request,token);
        assertThat(service.profile(MEMBER).information().targets().get("1003")).isEqualTo(target("70","112","g"));
        // Measurements go together, and only for adults.
        request.put("expectedVersion",4);var partial=new HashMap<String,Object>();partial.put("heightCm",180);request.put("body",partial);
        assertThat(request("PUT","/profile",request,token).body()).contains("height, weight and year of birth together");
        request.put("body",body("150","40",2015,null,null));assertThat(request("PUT","/profile",request,token).body()).contains("aged 16 to 100");
    }
    @Test void aiTargetsStartFromTheBodyEstimateAndFallBackToItWhenImplausible()throws Exception {
        aiTargets(MUSCLE);var request=profileWithoutTargets(0);request.put("goals","Build muscle");request.put("aiSummaryConsent",true);request.put("body",body("180","80",1996,"MALE","MODERATE"));
        request("PUT","/profile",request,token);var info=service.profile(MEMBER).information();
        assertThat(info.targets().get("1003").minimum()).isEqualByComparingTo("110");assertThat(info.targetSource()).contains("Generated by Qwen","2759 kcal");
        var sent=org.mockito.ArgumentCaptor.forClass(Object.class);verify(provider).json(anyString(),anyString(),anyString(),any(),sent.capture(),eq("HEALTH_TARGETS"));
        assertThat(json.writeValueAsString(sent.getValue())).contains("Build muscle","maintenanceKcal","2759","weightKg").doesNotContain(MEMBER,"Simple meals");
        // 1400 kcal is far below the 2759 kcal estimate, so the calculation is used instead, with the goal's 10% gain.
        aiTargets(MUSCLE.replace("\"minimum\":2200,\"maximum\":2600","\"minimum\":1300,\"maximum\":1500").replace("\"minimum\":250,\"maximum\":330","\"minimum\":120,\"maximum\":140").replace("\"minimum\":60,\"maximum\":85","\"minimum\":30,\"maximum\":40"));
        request.put("expectedVersion",1);request.put("body",body("180","80",1996,"MALE","ACTIVE"));request("PUT","/profile",request,token);
        var fallback=service.profile(MEMBER).information();
        assertThat(fallback.targetSource()).contains("Calculated from","far from the calculated estimate");assertThat(fallback.targets().get("1003")).isEqualTo(target("128","176","g"));
    }
    @Test void implausibleOrInconsistentAiTargetsFallBackToDefaults()throws Exception {
        aiTargets(MUSCLE.replace("2200","900"));var body=profileWithoutTargets(0);body.put("aiSummaryConsent",true);request("PUT","/profile",body,token);
        var info=service.profile(MEMBER).information();assertThat(info.targets()).isEqualTo(profileDefaults.targets());assertThat(info.targetSource()).contains("personalised targets unavailable");assertThat(info.targetRationale()).isNull();
        aiTargets(MUSCLE.replace("\"minimum\":250,\"maximum\":330","\"minimum\":50,\"maximum\":60"));body.put("expectedVersion",1);request("PUT","/profile",body,token);
        assertThat(service.profile(MEMBER).information().targetSource()).contains("did not match its energy target");
    }
    @Test void explanationDescribesAiTargetsUsingGoals(){
        aiTargets(MUSCLE);var body=input(0,List.of());service.saveProfile(MEMBER,new ProfileInput(0,body.timezone(),"Build muscle",body.allergies(),body.restrictions(),body.conditions(),body.preferences(),null,null,true));
        week(false,"600");var a=service.generate(MEMBER,WEEK);
        when(provider.json(anyString(),anyString(),anyString(),any(),any(),eq("HEALTH_AI"))).thenReturn(json.readTree("{\"choices\":[{\"message\":{\"content\":\"Targets follow your goal.\"}}]}"));
        explanation.explain(MEMBER,a.assessmentId);var sent=org.mockito.ArgumentCaptor.forClass(Object.class);verify(provider).json(anyString(),anyString(),anyString(),any(),sent.capture(),eq("HEALTH_AI"));
        assertThat(json.writeValueAsString(sent.getValue())).contains("targetBasis","Build muscle","higher protein").doesNotContain("peanut","vegetarian","Mild flavours");
    }
    @Test void profileEditsRetainExistingTargetsSourceAndOptionalNutrients()throws Exception {
        var original=input(0,List.of());var custom=new HashMap<>(original.targets());
        custom.put("1079",target("20","35","g"));
        service.saveProfile(MEMBER,new ProfileInput(0,original.timezone(),original.goals(),original.allergies(),
                original.restrictions(),original.conditions(),original.preferences(),original.targetSource(),custom,false));
        var edit=profileWithoutTargets(1);
        assertThat(request("PUT","/profile",edit,token).statusCode()).isEqualTo(200);
        var saved=service.profile(MEMBER);
        assertThat(saved.version()).isEqualTo(2);
        assertThat(saved.information().goals()).isEqualTo("Build regular eating habits");
        assertThat(saved.information().targets()).isEqualTo(custom);
        assertThat(saved.information().targetSource()).isEqualTo(original.targetSource());
        assertThat(request("PUT","/profile",edit,token).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_health_profile WHERE member_id=?",Integer.class,MEMBER)).isEqualTo(2);
    }
    @Test void optionalTargetFieldsStillRejectPartialOrInvalidExplicitValues()throws Exception {
        var body=profileWithoutTargets(0);body.put("targetSource","Supplied test targets");
        assertThat(request("PUT","/profile",body,token).statusCode()).isEqualTo(400);
        var invalid=new HashMap<>(targets());invalid.put("1008",target("1500","2100","g"));body.put("targets",invalid);
        assertThat(request("PUT","/profile",body,token).statusCode()).isEqualTo(400);
        body.put("targets",targets());body.put("targetSource"," ");
        assertThat(request("PUT","/profile",body,token).statusCode()).isEqualTo(400);
        body.put("targetSource","Supplied test targets");invalid.put("1008",target("2100","1500","kcal"));body.put("targets",invalid);
        assertThat(request("PUT","/profile",body,token).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_health_profile WHERE member_id=?",Integer.class,MEMBER)).isZero();
    }
    @Test void incompleteWeekIsDeferredAndMissingMealsAreNotTreatedAsZero(){
        profile();meal(MEMBER,WEEK.atStartOfDay(ZoneId.of("Australia/Sydney")).toInstant(),1,false,"600");
        var a=service.generate(MEMBER,WEEK);assertThat(a.status).isEqualTo(AssessmentStatus.INSUFFICIENT_INTAKE_DATA);assertThat(a.planningClearance).isEqualTo(Clearance.NOT_READY);assertThat(a.riskStatus).isEqualTo(Risk.NOT_ASSESSED);
        assertThatThrownBy(()->service.planningInput(MEMBER,a.assessmentId)).isInstanceOf(AgentException.class);
        assertThat(service.generate(MEMBER,WEEK).assessmentId).isEqualTo(a.assessmentId);
    }
    @Test void completeWeekUsesSevenDayAveragesAndRetainsHardConstraints(){
        profile();week(false,"600");var a=service.generate(MEMBER,WEEK);
        assertThat(a.intakeCount).isEqualTo(21);assertThat(a.coveredDays).isEqualTo(7);assertThat(a.planningClearance).isEqualTo(Clearance.CLEARED);
        assertThat(a.comparisons.stream().filter(c->c.nutrientId().equals("1008")).findFirst().orElseThrow().dailyAverage()).isEqualByComparingTo("1800");
        assertThat(service.planningInput(MEMBER,a.assessmentId).healthConstraintSet().excludedAllergens()).containsExactly("peanut");
        assertThat(service.generate(MEMBER,WEEK).assessmentId).isEqualTo(a.assessmentId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_health_assessment",Integer.class)).isEqualTo(1);
    }
    @Test void inboxDeduplicatesAndInternalOutboxDeliveryIsConnected(){
        var id=meal(MEMBER,NOW.minusSeconds(3600),1,false,"600");inbox.accept(id.toString());inbox.accept(id.toString());delivery.deliverPending();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM health_intake",Integer.class)).isEqualTo(1);assertThat(delivery.status(id).state()).isEqualTo("DELIVERED");
    }
    @Test void partialNutrientDoesNotClearEvenWithEnoughMeals(){
        profile();week(true,"600");var a=service.generate(MEMBER,WEEK);
        assertThat(a.planningClearance).isEqualTo(Clearance.NOT_READY);
        var protein=a.comparisons.stream().filter(c->c.nutrientId().equals("1003")).findFirst().orElseThrow();assertThat(protein.dailyAverage()).isNull();assertThat(protein.finding()).isEqualTo("MISSING_DATA");
    }
    @Test void deferredAssessmentResumesWithSameIdAfterNewRecords(){
        profile();var initial=service.generate(MEMBER,WEEK);week(false,"600");var updated=service.generate(MEMBER,WEEK);
        assertThat(updated.assessmentId).isEqualTo(initial.assessmentId);assertThat(updated.revision).isEqualTo(2);assertThat(updated.planningClearance).isEqualTo(Clearance.CLEARED);
    }
    @Test void profileChangeInvalidatesClearanceAndRetainsHistoricalIntakeEvidence(){
        profile();week(false,"600");var a=service.generate(MEMBER,WEEK);service.saveProfile(MEMBER,input(1,List.of()));
        assertThat(service.get(MEMBER,a.assessmentId).stale).isTrue();assertThatThrownBy(()->service.planningInput(MEMBER,a.assessmentId)).isInstanceOf(AgentException.class);
        var newer=service.generate(MEMBER,WEEK);assertThat(newer.profileVersion).isEqualTo(2);assertThat(newer.recordIds).isEqualTo(a.recordIds);assertThat(newer.assessmentId).isNotEqualTo(a.assessmentId);
    }
    @Test void healthConditionsBlockAndOnlyExpertCanClearWithAuditableDecision()throws Exception{
        service.saveProfile(MEMBER,input(0,List.of("Condition requiring review")));week(false,"600");var a=service.generate(MEMBER,WEEK);
        assertThat(a.planningClearance).isEqualTo(Clearance.BLOCKED_FOR_EXPERT_REVIEW);
        var input=new ReviewInput(Decision.MODIFIED_AND_CLEARED,"Targets reviewed; add a restriction",List.of("Additional test restriction"));
        assertThat(request("POST","/expert/assessments/"+a.assessmentId+"/review",input,token).statusCode()).isEqualTo(403);
        assertThat(request("GET","/expert/queue",null,token).statusCode()).isEqualTo(403);
        assertThat(service.queue(EXPERT,1,10).items()).hasSize(1);
        var reviewed=service.review(EXPERT,a.assessmentId,input);assertThat(reviewed.expertDecision.expertId()).isEqualTo(EXPERT);assertThat(reviewed.expertDecision.decidedAt()).isEqualTo(NOW);
        var planning=service.planningInput(MEMBER,a.assessmentId);assertThat(planning.expertDecision().decision()).isEqualTo(Decision.MODIFIED_AND_CLEARED);assertThat(planning.healthConstraintSet().excludedAllergens()).contains("peanut");assertThat(planning.healthConstraintSet().expertInstructions()).contains("Additional test restriction");
        assertThatThrownBy(()->service.review(EXPERT,a.assessmentId,input)).isInstanceOf(AgentException.class);
    }
    @Test void expertConsoleSeparatesPendingFromCompletedReviewsWithTotals()throws Exception{
        service.saveProfile(MEMBER,input(0,List.of()));week(false,"600");var automatic=service.generate(MEMBER,WEEK);assertThat(automatic.planningClearance).isEqualTo(Clearance.CLEARED);
        service.saveProfile(MEMBER,input(1,List.of("Condition requiring review")));var a=service.generate(MEMBER,WEEK);assertThat(a.planningClearance).isEqualTo(Clearance.BLOCKED_FOR_EXPERT_REVIEW);
        assertThat(service.queue(EXPERT,1,10).total()).isEqualTo(1);assertThat(service.reviewed(EXPERT,1,10).total()).isZero();
        assertThat(request("GET","/expert/reviewed",null,token).statusCode()).isEqualTo(403);
        service.review(EXPERT,a.assessmentId,new ReviewInput(Decision.CLEARED_FOR_PLANNING,"Targets suit the recorded condition",List.of()));
        var queue=service.queue(EXPERT,1,10);assertThat(queue.items()).isEmpty();assertThat(queue.total()).isZero();
        var done=service.reviewed(EXPERT,1,10);assertThat(done.total()).isEqualTo(1);
        assertThat(done.items()).singleElement().satisfies(x->{assertThat(x.assessmentId).isEqualTo(a.assessmentId);assertThat(x.expertDecision.decision()).isEqualTo(Decision.CLEARED_FOR_PLANNING);});
    }
    @Test void routineConditionsAreContextAndOnlyUnusualOnesNeedAnExpert(){
        service.saveProfile(MEMBER,input(0,List.of("Type 2 Diabetes")));week(false,"600");var routine=service.generate(MEMBER,WEEK);
        assertThat(routine.planningClearance).isEqualTo(Clearance.CLEARED);assertThat(routine.riskStatus).isNotEqualTo(Risk.POTENTIAL_HIGH_RISK);
        assertThat(routine.reasons).anyMatch(r->r.contains("Routine condition(s) Type 2 Diabetes"));
        service.saveProfile(MEMBER,input(1,List.of("Type 2 Diabetes","Chronic kidney disease")));var unusual=service.generate(MEMBER,WEEK);
        assertThat(unusual.planningClearance).isEqualTo(Clearance.BLOCKED_FOR_EXPERT_REVIEW);assertThat(unusual.reasons).anyMatch(r->r.contains("(Chronic kidney disease)"));
    }
    @Test void allergiesPlanningCannotInterpretNeedAnExpert(){
        service.saveProfile(MEMBER,new ProfileInput(0,"Australia/Sydney","Track meals",List.of("no seafood"),List.of(),List.of(),"Mild flavours","Targets supplied for testing",targets(),false));week(false,"600");
        assertThat(service.generate(MEMBER,WEEK).planningClearance).isEqualTo(Clearance.CLEARED);
        service.saveProfile(MEMBER,new ProfileInput(1,"Australia/Sydney","Track meals",List.of("no seafood","Kiwifruit"),List.of(),List.of(),"Mild flavours","Targets supplied for testing",targets(),false));var unknown=service.generate(MEMBER,WEEK);
        assertThat(unknown.planningClearance).isEqualTo(Clearance.BLOCKED_FOR_EXPERT_REVIEW);assertThat(unknown.reasons).anyMatch(r->r.contains("(Kiwifruit)"));
    }
    @Test void referralAndIneligibilityNeverReleasePlanning(){
        profile();week(false,"2000");var a=service.generate(MEMBER,WEEK);assertThat(a.riskStatus).isEqualTo(Risk.POTENTIAL_HIGH_RISK);
        service.review(EXPERT,a.assessmentId,new ReviewInput(Decision.REFER_TO_HEALTHCARE,"Further assessment required",List.of()));assertThatThrownBy(()->service.planningInput(MEMBER,a.assessmentId)).isInstanceOf(AgentException.class);
    }
    @Test void accountIsolationAndNoClientSubmittedIntakeEndpoint()throws Exception{
        profile();week(false,"600");var a=service.generate(MEMBER,WEEK);
        assertThat(request("GET","/assessments/"+a.assessmentId,null,otherToken).statusCode()).isEqualTo(404);
        assertThat(request("GET","/assessments/"+a.assessmentId+"/planning-input",null,otherToken).statusCode()).isEqualTo(404);
        assertThat(service.list(OTHER,1,10).items()).isEmpty();
        assertThat(request("POST","/intakes",Map.of(),token).statusCode()).isNotEqualTo(200);
    }
    @Test void localWeekBoundariesExcludeMealsOutsidePeriod(){
        profile();week(false,"600");meal(MEMBER,WEEK.atStartOfDay(ZoneId.of("Australia/Sydney")).toInstant().minusSeconds(1),1,false,"900");meal(MEMBER,WEEK.plusDays(7).atStartOfDay(ZoneId.of("Australia/Sydney")).toInstant(),1,false,"900");
        assertThat(service.generate(MEMBER,WEEK).intakeCount).isEqualTo(21);
        assertThatThrownBy(()->service.generate(MEMBER,WEEK.plusWeeks(1))).isInstanceOf(AgentException.class);
        assertThat(service.generate(MEMBER,WEEK.plusDays(1)).periodStart).isEqualTo(WEEK.plusDays(1));
    }
    @Test void aiRequiresExplicitConsentAndDoesNotCallProvider(){profile();var a=service.generate(MEMBER,WEEK);assertThatThrownBy(()->explanation.explain(MEMBER,a.assessmentId)).isInstanceOf(AgentException.class).hasMessageContaining("consent");verifyNoInteractions(provider);}
    @Test void parallelGenerationProducesOneAssessment()throws Exception{
        profile();week(false,"600");var executor=Executors.newFixedThreadPool(2);
        try{var one=executor.submit(()->service.generate(MEMBER,WEEK).assessmentId);var two=executor.submit(()->service.generate(MEMBER,WEEK).assessmentId);assertThat(one.get(10,TimeUnit.SECONDS)).isEqualTo(two.get(10,TimeUnit.SECONDS));}finally{executor.shutdownNow();}
    }
    @Test void futureProfileVersionBlocksPlanning(){profile();week(false,"600");meal(MEMBER,WEEK.atTime(22,0).atZone(ZoneId.of("Australia/Sydney")).toInstant(),999,false,"100");assertThat(service.generate(MEMBER,WEEK).planningClearance).isEqualTo(Clearance.NOT_READY);}
    @Test void aiExplanationUsesOnlyConsentedAggregateAndCannotChangeClearance(){
        var base=input(0,List.of("private condition"));
        service.saveProfile(MEMBER,new ProfileInput(0,base.timezone(),base.goals(),base.allergies(),base.restrictions(),base.conditions(),base.preferences(),base.targetSource(),base.targets(),true));
        week(false,"600");var a=service.generate(MEMBER,WEEK);
        when(provider.json(anyString(),anyString(),anyString(),any(),any(),eq("HEALTH_AI")))
                .thenReturn(json.readTree("{\"choices\":[{\"message\":{\"content\":\"Please check the review status above.\"}}]}"));
        var explained=explanation.explain(MEMBER,a.assessmentId);
        assertThat(explained.aiExplanation).isEqualTo("Please check the review status above.");
        assertThat(explained.planningClearance).isEqualTo(Clearance.BLOCKED_FOR_EXPERT_REVIEW);
        var body=org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(provider).json(anyString(),anyString(),anyString(),any(),body.capture(),eq("HEALTH_AI"));
        assertThat(json.writeValueAsString(body.getValue())).doesNotContain(MEMBER,"private condition","peanut",base.targetSource())
                .contains("respond exclusively in English");
        explanation.explain(MEMBER,a.assessmentId);verifyNoMoreInteractions(provider);
    }
    @Test void expertCannotReviewOwnAssessmentOrReleaseStaleAssessment(){
        service.saveProfile(EXPERT,input(0,List.of("review")));
        for(int d=0;d<7;d++)for(int m=0;m<3;m++)meal(EXPERT,WEEK.plusDays(d).atTime(8+m*5,0).atZone(ZoneId.of("Australia/Sydney")).toInstant(),1,false,"600");
        var own=service.generate(EXPERT,WEEK);
        var decision=new ReviewInput(Decision.CLEARED_FOR_PLANNING,"reviewed",List.of());
        assertThatThrownBy(()->service.review(EXPERT,own.assessmentId,decision)).hasMessageContaining("own");
        service.saveProfile(MEMBER,input(0,List.of("review")));week(false,"600");var a=service.generate(MEMBER,WEEK);
        service.saveProfile(MEMBER,input(1,List.of()));
        assertThatThrownBy(()->service.review(EXPERT,a.assessmentId,decision)).hasMessageContaining("updated");
    }
    @Test void notEligibleDecisionStaysBlocked(){
        service.saveProfile(MEMBER,input(0,List.of("review")));week(false,"600");var a=service.generate(MEMBER,WEEK);
        service.review(EXPERT,a.assessmentId,new ReviewInput(Decision.NOT_ELIGIBLE_FOR_AUTOMATION,"Not suitable for automation",List.of()));
        assertThatThrownBy(()->service.planningInput(MEMBER,a.assessmentId)).isInstanceOf(AgentException.class);
    }
    @Test void newUploadRejectsOutdatedHealthProfileVersionBeforeCallingProviders(){
        profile();service.saveProfile(MEMBER,input(1,List.of()));
        assertThatThrownBy(()->dietary.submit(UUID.randomUUID(),MEMBER,1,NOW,true,new byte[0]))
                .isInstanceOf(AgentException.class).hasMessageContaining("profile version has changed")
                .extracting(e->((AgentException)e).code()).isEqualTo("PROFILE_VERSION_CONFLICT");
        verifyNoInteractions(provider);
    }
    @Test void scheduledCatchUpUsesLastSevenDatesAndIsIdempotent(){
        profile();var start=NOW.atZone(ZoneId.of("Australia/Sydney")).toLocalDate().minusDays(7);
        for(int d=0;d<7;d++)for(int m=0;m<3;m++)meal(MEMBER,start.plusDays(d).atTime(8+m*5,0).atZone(ZoneId.of("Australia/Sydney")).toInstant(),1,false,"600");
        var scheduler=new HealthScheduler(service,health,clock);
        scheduler.assessPreviousWeek();scheduler.assessPreviousWeek();
        var results=service.list(MEMBER,1,10).items();assertThat(results).hasSize(1);
        assertThat(results.get(0).periodStart).isEqualTo(start);assertThat(results.get(0).planningClearance).isEqualTo(Clearance.CLEARED);
    }
    @Test void rollingCoverageChecksEachDateAndCreatesNoReportUntilMemberChooses()throws Exception {
        profile();var start=WEEK.plusDays(1);var zone=ZoneId.of("Australia/Sydney");
        for(int d=0;d<7;d++)for(int m=0;m<(d==3?1:3);m++)meal(MEMBER,start.plusDays(d).atTime(8+m*5,0).atZone(zone).toInstant(),1,false,"600");
        var response=request("GET","/assessment-coverage?periodStart="+start,null,token);
        assertThat(response.statusCode()).isEqualTo(200);var c=json.readValue(response.body(),IntakeCoverage.class);
        assertThat(c.intakeCount()).isEqualTo(19);assertThat(c.observedDays()).isEqualTo(7);assertThat(c.coveredDays()).isEqualTo(6);
        assertThat(c.missingMeals()).isEqualTo(2);assertThat(c.incompleteDays()).containsExactly(start.plusDays(3));
        assertThat(service.list(MEMBER,1,10).items()).isEmpty();
        assertThat(request("GET","/assessment-coverage?periodStart="+start,null,otherToken).statusCode()).isEqualTo(404);
        var result=request("POST","/assessments",Map.of("periodStart",start,"allowIncomplete",true),token);
        assertThat(result.statusCode()).isEqualTo(200);var a=json.readValue(result.body(),Assessment.class);
        assertThat(a.status).isEqualTo(AssessmentStatus.COMPLETED);assertThat(a.riskStatus).isEqualTo(Risk.LIMITED_DATA);
        assertThat(a.partialDataAccepted).isTrue();assertThat(a.incompleteRecords).isTrue();
        var planning=service.planningInput(MEMBER,a.assessmentId);
        assertThat(planning.incompleteRecords()).isTrue();assertThat(planning.intakeCount()).isEqualTo(19);
        assertThat(planning.healthConstraintSet().excludedAllergens()).containsExactly("peanut");
        assertThat(planning.comparisons()).allSatisfy(n->assertThat(n.finding()).startsWith("RECORDED_"));
    }
    @Test void partialAssessmentUsesOnlyObservedDatesAndCanBeAcceptedWithoutAddingMeals() {
        profile();var zone=ZoneId.of("Australia/Sydney");
        meal(MEMBER,WEEK.atTime(12,0).atZone(zone).toInstant(),1,false,"600");
        meal(MEMBER,WEEK.plusDays(4).atTime(12,0).atZone(zone).toInstant(),1,false,"800");
        var waiting=service.generate(MEMBER,WEEK);assertThat(waiting.partialDataAccepted).isFalse();
        var accepted=service.generate(MEMBER,WEEK,true);
        assertThat(accepted.assessmentId).isEqualTo(waiting.assessmentId);assertThat(accepted.revision).isEqualTo(2);
        assertThat(accepted.planningClearance).isEqualTo(Clearance.CLEARED);assertThat(accepted.observedDays).isEqualTo(2);
        var energy=accepted.comparisons.stream().filter(c->c.nutrientId().equals("1008")).findFirst().orElseThrow();
        assertThat(energy.total()).isEqualByComparingTo("1400");assertThat(energy.dailyAverage()).isEqualByComparingTo("700");
        assertThat(energy.finding()).isEqualTo("RECORDED_BELOW_TARGET");assertThat(energy.belowDays()).isZero();
        assertThat(service.generate(MEMBER,WEEK).revision).isEqualTo(2);
    }
    @Test void totalMealCountDoesNotHideMissingDatesAndPartialReportsCanIncorporateAddedMeals() {
        profile();var zone=ZoneId.of("Australia/Sydney");
        for(int i=0;i<21;i++)meal(MEMBER,WEEK.atTime(8,0).atZone(zone).toInstant().plusSeconds(i),1,false,"50");
        var c=service.coverage(MEMBER,WEEK);assertThat(c.missingMeals()).isEqualTo(18);assertThat(c.coveredDays()).isEqualTo(1);
        var a=service.generate(MEMBER,WEEK,true);assertThat(a.status).isEqualTo(AssessmentStatus.COMPLETED);
        for(int d=1;d<7;d++)for(int m=0;m<3;m++)meal(MEMBER,WEEK.plusDays(d).atTime(8+m*5,0).atZone(zone).toInstant(),1,false,"600");
        var updated=service.generate(MEMBER,WEEK);
        assertThat(updated.assessmentId).isEqualTo(a.assessmentId);assertThat(updated.revision).isEqualTo(2);
        assertThat(updated.incompleteRecords).isFalse();assertThat(updated.observedDays).isEqualTo(7);
        assertThat(updated.riskStatus).isNotEqualTo(Risk.LIMITED_DATA);
    }
    @Test void partialConsentCannotInventEmptyIntakeOrMissingNutrientsOrBypassExpertReview() {
        profile();var empty=service.generate(MEMBER,WEEK,true);
        assertThat(empty.status).isEqualTo(AssessmentStatus.INSUFFICIENT_INTAKE_DATA);assertThat(empty.partialDataAccepted).isFalse();
        assertThat(empty.comparisons).allSatisfy(c->assertThat(c.dailyAverage()).isNull());
        meal(MEMBER,WEEK.atTime(12,0).atZone(ZoneId.of("Australia/Sydney")).toInstant(),1,true,"600");
        var missing=service.generate(MEMBER,WEEK,true);assertThat(missing.planningClearance).isEqualTo(Clearance.NOT_READY);
        service.saveProfile(MEMBER,input(1,List.of("Condition requiring review")));
        var start=WEEK.plusDays(1);meal(MEMBER,start.atTime(12,0).atZone(ZoneId.of("Australia/Sydney")).toInstant(),2,false,"600");
        var review=service.generate(MEMBER,start,true);
        assertThat(review.planningClearance).isEqualTo(Clearance.BLOCKED_FOR_EXPERT_REVIEW);
        assertThatThrownBy(()->service.planningInput(MEMBER,review.assessmentId)).isInstanceOf(AgentException.class);
        service.review(EXPERT,review.assessmentId,new ReviewInput(Decision.MODIFIED_AND_CLEARED,"Review partial records and targets",List.of("Retain this reviewed restriction")));
        var frozen=service.generate(MEMBER,start,true);assertThat(frozen.expertDecision).isNotNull();
        assertThat(service.planningInput(MEMBER,frozen.assessmentId).healthConstraintSet().expertInstructions()).containsExactly("Retain this reviewed restriction");
    }
    @Test void rollingRangeValidationUsesProfileTimezoneAndIncludesSevenCompletedDates()throws Exception {
        profile();when(clock.instant()).thenReturn(Instant.parse("2026-10-12T14:30:00Z")); // Already October 13 in Sydney.
        token=tokens.issue(users.findById(MEMBER)).accessToken();
        assertThat(service.coverage(MEMBER,LocalDate.parse("2026-10-06")).periodEnd()).isEqualTo(LocalDate.parse("2026-10-12"));
        assertThat(request("GET","/assessment-coverage?periodStart=2026-10-07",null,token).statusCode()).isEqualTo(400);
        assertThat(request("POST","/assessments",Map.of("periodStart","2024-10-12","allowIncomplete",true),token).statusCode()).isEqualTo(400);
        assertThat(request("POST","/assessments",Map.of("allowIncomplete",true),token).statusCode()).isEqualTo(400);
    }
    @Test void daylightSavingWeekUsesLocalCalendarRatherThan168Hours(){
        profile();var start=LocalDate.parse("2026-09-28");var zone=ZoneId.of("Australia/Sydney");
        var first=start.atStartOfDay(zone).toInstant();var end=start.plusDays(7).atStartOfDay(zone).toInstant();
        assertThat(Duration.between(first,end).toHours()).isEqualTo(167);
        var included=meal(MEMBER,end.minusSeconds(1),1,false,"600");
        meal(MEMBER,end,1,false,"600");meal(MEMBER,first.minusSeconds(1),1,false,"600");
        var result=service.generate(MEMBER,start);assertThat(result.recordIds).containsExactly(included.toString());
    }
    @Test void onDemandSynchronizationRepairsPriorDeliveryFailure(){
        profile();var id=meal(MEMBER,WEEK.atTime(8,0).atZone(ZoneId.of("Australia/Sydney")).toInstant(),1,false,"600");
        for(int i=0;i<3;i++)outbox.incrementAttempts(id.toString());outbox.recordFailure(id.toString(),"HEALTH_UNAVAILABLE");
        assertThat(delivery.status(id).state()).isEqualTo("DELIVERY_FAILED");
        service.generate(MEMBER,WEEK);assertThat(delivery.status(id).state()).isEqualTo("DELIVERED");
        assertThat(outbox.find(id.toString()).lastError()).isNull();
    }
}
