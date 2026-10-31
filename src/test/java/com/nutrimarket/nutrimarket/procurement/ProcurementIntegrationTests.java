package com.nutrimarket.nutrimarket.procurement;

import com.nutrimarket.nutrimarket.dao.market.MarketMapper;
import com.nutrimarket.nutrimarket.model.procurement.ProcurementModels;
import com.nutrimarket.nutrimarket.model.procurement.ProcurementModels.*;
import com.nutrimarket.nutrimarket.service.procurement.ProcurementService;
import com.nutrimarket.nutrimarket.service.market.*;
import com.nutrimarket.nutrimarket.integration.market.MarketSemanticClient;
import com.nutrimarket.nutrimarket.model.market.MarketModels.*;
import com.nutrimarket.nutrimarket.model.market.MarketModels.State;

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
    "spring.datasource.url=jdbc:h2:mem:procurement-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.sql.init.schema-locations=classpath:schema-h2.sql",
    "nutrimarket.market.operator-usernames[0]=reviewer",
    "nutrimarket.procurement.report-stores.reviewer=Store A","nutrimarket.procurement.max-checkouts=5","nutrimarket.procurement.store-addresses[Store\\ A]=1 Test Street, Sydney NSW 2000",
    "nutrimarket.procurement.store-fees[0].store=Store A","nutrimarket.procurement.store-fees[0].currency=AUD","nutrimarket.procurement.store-fees[0].delivery=8",
    "nutrimarket.procurement.store-fees[1].store=Store B","nutrimarket.procurement.store-fees[1].currency=AUD","nutrimarket.procurement.store-fees[1].delivery=8",
    "nutrimarket.health.expert-usernames[0]=reviewer","nutrimarket.health.expert-usernames[1]=member",
    "nutrimarket.procurement.retailer.simulated-outcomes[0].store=Store B","nutrimarket.procurement.retailer.simulated-outcomes[0].outcome=REJECT",
    "nutrimarket.procurement.retailer.simulated-outcomes[0].first-attempt-only=true",
    "nutrimarket.procurement.retailer.simulated-outcomes[1].store=Store D","nutrimarket.procurement.retailer.simulated-outcomes[1].outcome=PARTIAL",
    "nutrimarket.procurement.retailer.simulated-outcomes[1].skus[0]=D-lentils","nutrimarket.procurement.retailer.simulated-outcomes[1].reason=Out of stock at picking",
    "nutrimarket.dietary.delivery-delay-ms=3600000"})
class ProcurementIntegrationTests {
    @Autowired ProcurementService procurement;@Autowired com.nutrimarket.nutrimarket.service.procurement.PantryService pantry;@Autowired com.nutrimarket.nutrimarket.service.pipeline.MealPipeline pipeline;
    @Autowired com.nutrimarket.nutrimarket.service.procurement.BasketEngine baskets;
    @Autowired MarketService market;@Autowired MarketMapper markets;@Autowired CatalogService catalog;
    @MockitoBean MarketSemanticClient matcher;
    static final String MEMBER="00000000-0000-0000-0000-000000000301",OTHER="00000000-0000-0000-0000-000000000302",EXPERT="00000000-0000-0000-0000-000000000303";
    static final Instant NOW=Instant.parse("2026-10-13T00:00:00Z");
    static final LocalDate WEEK=LocalDate.parse("2026-10-19");
    @Autowired PlanningService planning;@Autowired PlanningMapper mapper;@Autowired PlanningEngine engine;
    @Autowired HealthService health;@Autowired HealthMapper healthMapper;@Autowired UserMapper users;
    @Autowired JwtTokenService tokens;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    @Autowired com.nutrimarket.nutrimarket.config.MarketPolicy marketPolicy;
    @MockitoBean Clock clock;@MockitoBean NutritionClient nutrition;@MockitoBean PlanningClient model;
    @Value("${local.server.port}") int port;
    String token,otherToken,expertToken;
    @BeforeEach void setup(){
        when(clock.instant()).thenReturn(NOW);
        for(String table:List.of("member_food_dislike","member_delivery_address","member_pantry","procurement_order","shopping_basket","market_outbox","market_result","market_product_history","market_product","planning_outbox","nutrition_plan","weekly_health_assessment","health_intake","member_health_profile","dietary_outbox","dietary_record","member_account"))jdbc.update("DELETE FROM "+table);
        when(matcher.match(any(),anyList())).thenAnswer(call->{List<Product> products=call.getArgument(1);Map<String,BigDecimal> values=new HashMap<>();products.forEach(p->values.put(p.productId(),BigDecimal.ONE));return values;});
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
    static Candidate candidate(String ingredient){List<CandidateMeal> meals=new ArrayList<>();for(int day=0;day<7;day++)for(var slot:Slot.values())meals.add(meal(day,slot,ingredient));return new Candidate(meals);}
    Plan generate(Assessment a){return planning.generate(MEMBER,new Generate(UUID.fromString(a.assessmentId),WEEK,true));}
    Plan publish(Plan p){return planning.publish(MEMBER,p.planId,new Publish(p.version,true));}
    Replan replan(int version,String line,String ingredient){return new Replan(UUID.randomUUID(),version,ReplanReason.AVAILABILITY,"Supplier evidence: ingredient unavailable for this meal",List.of(line),List.of(ingredient),true);}
    HttpResponse<String> request(String method,String path,Object body,String bearer)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/procurement"+path));if(bearer!=null)b.header("Authorization","Bearer "+bearer);
        b.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    ProductInput facts(){return new ProductInput(0,"verified-store-catalogue","RICE-01","Cooked brown rice pouch","Fixture brand","Cooked grains","rice","READY_TO_EAT",null,"Store A","AUD",n("3"),n("500"),"",Availability.IN_STOCK,NOW,"https://example.invalid/rice",List.of("brown rice"),List.of(),List.of(),List.of("vegan"),true,true,nutrients(),true,true);}
    ProductInput change(ProductInput base,Map<String,Object> changes){var map=json.convertValue(base,new tools.jackson.core.type.TypeReference<Map<String,Object>>(){});map.putAll(changes);return json.convertValue(map,ProductInput.class);}
    Product product(Map<String,Object> changes){return catalog.save(EXPERT,null,change(facts(),changes));}
    Plan published(){return publish(generate(assessment(List.of())));}
    MarketResult match(Plan p){return market.start(MEMBER,new com.nutrimarket.nutrimarket.model.market.MarketModels.Start(UUID.fromString(p.planId),p.version));}
    Refresh refresh(MarketResult r,List<String> lines){return new Refresh(UUID.randomUUID(),r.revision,lines);}
    List<String> allLines(MarketResult r){return r.planning.weeklyMealPlan().stream().map(Meal::menuLineId).toList();}
    Basket basket(){product(Map.of());return procurement.start(MEMBER,new ProcurementModels.Start(UUID.fromString(match(published()).resultId)));}
    Basket budget(Basket b,String amount){return procurement.change(MEMBER,b.basketId,new Change(b.version,new Settings(n(amount),"AUD",Preference.ECONOMY,Fulfilment.PICKUP,List.of(),List.of())));}
    Basket checkout(Basket b){return procurement.checkout(MEMBER,b.basketId,new Checkout(b.version,UUID.randomUUID()));}
    Confirm confirm(Basket b){return new Confirm(b.version,UUID.randomUUID(),true);}
    Plan automaticPlan(){
        var base=input(0,List.of());
        assessment(new ProfileInput(0,base.timezone(),base.goals(),base.allergies(),base.restrictions(),base.conditions(),base.preferences(),base.targetSource(),base.targets(),false,null,true,null));
        pipeline.advance(MEMBER);
        return planning.get(MEMBER,pipeline.status(MEMBER).planId());
    }
    @Test void clearedAssessmentFlowsToABasketWithoutMemberClicks()throws Exception{
        assertThat(pipeline.status(MEMBER).stage().name()).isEqualTo("NO_PROFILE");
        assessment(List.of());assertThat(pipeline.status(MEMBER).stage().name()).isEqualTo("CONSENT_REQUIRED");
        pipeline.advance(MEMBER);verifyNoInteractions(model);
        product(Map.of());var base=input(1,List.of());
        assessment(new ProfileInput(1,base.timezone(),base.goals(),base.allergies(),base.restrictions(),base.conditions(),base.preferences(),base.targetSource(),base.targets(),false,null,true,null));
        assertThat(pipeline.status(MEMBER).stage().name()).isEqualTo("PLANNING");
        pipeline.advance(MEMBER);pipeline.advance(MEMBER);
        var status=pipeline.status(MEMBER);assertThat(status.stage().name()).isEqualTo("MATCHING");assertThat(status.planState()).isEqualTo("PUBLISHED");
        var plan=planning.get(MEMBER,status.planId());assertThat(plan.audit).anyMatch(e->e.action().equals("AUTO_PUBLISHED"));assertThat(plan.weekStart).isEqualTo(LocalDate.parse("2026-10-14"));
        verify(model,times(1)).propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList());
        // Outbox deliveries are disabled in tests; run the in-process hand-offs they perform.
        var result=match(plan);procurement.receive(MEMBER,result.resultId,result.revision);
        var ready=pipeline.status(MEMBER);assertThat(ready.stage().name()).isEqualTo("BASKET_READY");assertThat(procurement.get(MEMBER,ready.basketId()).evaluation.lines()).isNotEmpty();
        assertThat(request("GET","/pipeline",null,token).body()).contains("BASKET_READY",ready.basketId());
    }
    @Test void changedCatalogueResumesAutomaticMatchingAndCreatesBasketOnlyOnce(){
        var product=product(Map.of());var plan=automaticPlan();var result=match(plan);
        catalog.save(EXPERT,product.productId(),change(facts(),Map.of("expectedVersion",product.version())));
        clearInvocations(matcher,model);
        var paused=pipeline.status(MEMBER);
        assertThat(paused.stage().name()).isEqualTo("MATCH_PAUSED");
        assertThat(paused.message()).contains("changed or expired");
        assertThat(markets.delivery(result.resultId,1).state()).isEqualTo("CANCELLED");
        verifyNoInteractions(matcher,model); // Status reads never spend a model request.
        pipeline.advance(MEMBER);
        var refreshed=market.get(MEMBER,result.resultId);
        assertThat(refreshed.state).isEqualTo(State.COMPLETED);
        assertThat(refreshed.revision).isEqualTo(2);assertThat(refreshed.refreshCount).isEqualTo(1);
        assertThat(pipeline.status(MEMBER).stage().name()).isEqualTo("BUILDING_BASKET");
        var delivery=new MarketDelivery(markets,market,marketPolicy,procurement);
        delivery.deliverPending();pipeline.advance(MEMBER);delivery.deliverPending();
        assertThat(pipeline.status(MEMBER).stage().name()).isEqualTo("BASKET_READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopping_basket",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nutrition_plan",Integer.class)).isEqualTo(1);
        verify(matcher,times(1)).match(any(),anyList());verifyNoInteractions(model);
    }
    @Test void interruptedAutomaticMatchingResumesThroughBoundedRefresh(){
        product(Map.of());var plan=automaticPlan();var result=match(plan);
        result.state=State.MATCHING;result.updatedAt=NOW.minus(Duration.ofMinutes(21));
        markets.update(result,json.writeValueAsString(result));clearInvocations(matcher);
        assertThat(pipeline.status(MEMBER).stage().name()).isEqualTo("MATCH_PAUSED");
        pipeline.advance(MEMBER);
        var refreshed=market.get(MEMBER,result.resultId);
        assertThat(refreshed.state).isEqualTo(State.COMPLETED);
        assertThat(refreshed.revision).isEqualTo(2);assertThat(refreshed.refreshCount).isEqualTo(1);
        verify(matcher,times(1)).match(any(),anyList());
    }
    @Test void automaticMatchingHonoursRefreshLimitAndExplainsThePause(){
        var product=product(Map.of());var result=match(automaticPlan());
        result.refreshCount=marketPolicy.maxRefreshes();markets.update(result,json.writeValueAsString(result));
        catalog.save(EXPERT,product.productId(),change(facts(),Map.of("expectedVersion",product.version())));
        clearInvocations(matcher);pipeline.advance(MEMBER);pipeline.advance(MEMBER);
        var status=pipeline.status(MEMBER);
        assertThat(status.stage().name()).isEqualTo("MATCH_PAUSED");
        assertThat(status.message()).contains("limit has been reached");
        assertThat(market.get(MEMBER,result.resultId).revision).isEqualTo(1);
        verifyNoInteractions(matcher);
    }
    @Test void failedBasketHandoffIsVisibleAndManualRetryUsesExistingMatches()throws Exception{
        product(Map.of());var result=match(automaticPlan());
        assertThat(pipeline.status(MEMBER).stage().name()).isEqualTo("BUILDING_BASKET");
        markets.delivered(result.resultId,result.revision,"DELIVERY_FAILED");clearInvocations(matcher,model);
        var status=pipeline.status(MEMBER);
        assertThat(status.stage().name()).isEqualTo("BASKET_PAUSED");
        assertThat(status.message()).contains("could not be created");
        assertThat(request("GET","/pipeline",null,token).body()).contains("BASKET_PAUSED");
        assertThat(request("POST","/baskets",new ProcurementModels.Start(UUID.fromString(result.resultId)),token).statusCode()).isEqualTo(200);
        assertThat(pipeline.status(MEMBER).stage().name()).isEqualTo("BASKET_READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM procurement_order",Integer.class)).isZero();
        verifyNoInteractions(matcher,model);
    }
    @Test void shelfStableLeftoversGoToThePantryAndCutTheNextBasket(){
        var scaled=new TreeMap<String,Nutrient>();nutrients().forEach((k,v)->scaled.put(k,new Nutrient(v.amount().multiply(n("2.9")),v.unit())));
        product(Map.of("preparation","RAW","cookedYieldFactor",n("2.9"),"packageGrams",n("5000"),"per100g",scaled));
        var a=assessment(List.of());var first=publish(generate(a));
        var b=checkout(budget(procurement.start(MEMBER,new ProcurementModels.Start(UUID.fromString(match(first).resultId))),"100"));
        var order=procurement.confirm(MEMBER,b.basketId,confirm(b));assertThat(order.status).isEqualTo("CONFIRMED");
        // 21 meals × 200 g cooked rice = 4200 g, or 1448.3 g dry at yield 2.9; the rest of the 5 kg bag stays in the pantry.
        var view=pantry.view(MEMBER);
        assertThat(view.stock()).singleElement().satisfies(i->{assertThat(i.ingredientId()).isEqualTo("rice");assertThat(i.remainingGrams()).isEqualByComparingTo("3551.7");});
        assertThat(view.lastWeek()).singleElement().satisfies(p->assertThat(p.keptInPantry()).isTrue());
        var second=publish(planning.generate(MEMBER,new Generate(UUID.fromString(a.assessmentId),WEEK.plusWeeks(1),true)));
        var next=budget(procurement.start(MEMBER,new ProcurementModels.Start(UUID.fromString(match(second).resultId))),"100");
        assertThat(next.evaluation.lines()).singleElement().satisfies(l->{assertThat(l.packages()).isEqualByComparingTo("0");assertThat(l.pantryGrams()).isEqualByComparingTo("4200");});
        assertThat(next.evaluation.total()).isEqualByComparingTo("0");assertThat(next.evaluation.fees()).isEmpty();
        // A correction to 500 g dry (1450 g cooked) leaves 2750 g to buy.
        pantry.correct(MEMBER,new PantryAdjustment(view.stock().get(0).key(),n("500")));
        var partial=budget(next,"100");
        assertThat(partial.evaluation.lines()).singleElement().satisfies(l->{assertThat(l.packages()).isEqualByComparingTo("1");assertThat(l.pantryGrams()).isEqualByComparingTo("1450");});
        procurement.cancel(MEMBER,order.orderId);assertThat(pantry.view(MEMBER).stock()).isEmpty();
    }
    @Test void combinesPackagesAcrossMealsAndPreservesPortionEvidence(){var b=budget(basket(),"100");assertThat(b.state).isEqualTo(ProcurementModels.State.DRAFT);assertThat(b.evaluation.lines()).hasSize(1);var line=b.evaluation.lines().get(0);assertThat(line.allocations()).hasSize(21);assertThat(line.requiredSoldGrams()).isEqualByComparingTo("4200");assertThat(line.packages()).isEqualByComparingTo("9");assertThat(line.surplusGrams()).isEqualByComparingTo("300");assertThat(b.evaluation.total()).isEqualByComparingTo("27");assertThat(line.allocations()).allSatisfy(a->assertThat(a.evidence().hardConstraintStatus()).isEqualTo(Compliance.COMPLIANT));}
    Settings edited(Basket b,ItemEdit... edits){var s=b.settings;return new Settings(n("100"),s.currency(),Preference.ECONOMY,Fulfilment.PICKUP,s.preferredStores(),s.excludedProductIds(),false,s.exclusionList(),List.of(edits));}
    @Test void dislikedProductIsRemovedAndReplacedOnlyWhenASuitableAlternativeExists(){
        var cheap=product(Map.of("sku","cheap","price",n("2")));var alternative=product(Map.of("sku","alternative","price",n("2.5")));
        var b=budget(start(published()),"100");var edit=new ItemEdit(UUID.fromString(cheap.productId()),ItemChoice.DISLIKE,List.of("forged-ingredient"));
        var changed=procurement.change(MEMBER,b.basketId,new Change(b.version,edited(b,edit)));
        assertThat(changed.evaluation.lines()).singleElement().satisfies(l->assertThat(l.product().productId()).isEqualTo(alternative.productId()));
        assertThat(changed.settings.itemEditList().get(0).ingredientIds()).containsExactly("rice");
        var blocked=procurement.change(MEMBER,b.basketId,new Change(changed.version,edited(changed,edit,new ItemEdit(UUID.fromString(alternative.productId()),ItemChoice.DISLIKE))));
        assertThat(blocked.state).isEqualTo(ProcurementModels.State.BLOCKED);assertThat(blocked.evaluation.lines()).isEmpty();
        var restored=procurement.change(MEMBER,b.basketId,new Change(blocked.version,edited(blocked)));
        assertThat(restored.state).isEqualTo(ProcurementModels.State.DRAFT);assertThat(restored.evaluation.total()).isEqualByComparingTo("18");
    }
    static Candidate withLentils(){List<CandidateMeal> meals=new ArrayList<>();for(int day=0;day<7;day++)for(var slot:Slot.values())meals.add(meal(day,slot,day==0&&slot==Slot.DINNER||day==3&&slot==Slot.LUNCH?"lentils":"rice"));return new Candidate(meals);}
    @Test void dislikedFoodIsRememberedAndAgent3ReplacesOnlyTheMealsThatUseIt(){
        product(Map.of("sku","rice","price",n("2")));var lentils=product(Map.of("sku","lentils","ingredientId","lentils","ingredients",List.of("Brown lentils (100%)"),"price",n("2")));
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(withLentils());
        var a=assessment(List.of());var p=publish(generate(a));var b=budget(start(p),"100");
        var edit=new ItemEdit(UUID.fromString(lentils.productId()),ItemChoice.DISLIKE_FOOD);
        // Changing meals sends a replan request to Qwen, so it needs consent; without it nothing is saved or remembered.
        assertThatThrownBy(()->procurement.change(MEMBER,b.basketId,new Change(b.version,edited(b,edit)))).isInstanceOf(AgentException.class).hasMessageContaining("Agree to AI replanning");
        assertThat(planning.dislikes(MEMBER)).isEmpty();assertThat(procurement.get(MEMBER,b.basketId).version).isEqualTo(b.version);
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(new Candidate(List.of(meal(0,Slot.DINNER,"rice"),meal(3,Slot.LUNCH,"rice"))));
        var changed=procurement.change(MEMBER,b.basketId,new Change(b.version,edited(b,edit),true));
        assertThat(changed.state).isEqualTo(ProcurementModels.State.STALE);assertThat(changed.replanStatus).isEqualTo("REQUESTED");
        assertThat(changed.replanRequest.reason()).isEqualTo(ReplanReason.PREFERENCE);assertThat(changed.replanRequest.menuLineIds()).containsExactlyInAnyOrder("d0-DINNER","d3-LUNCH");
        assertThat(changed.settings.itemEditList()).singleElement().satisfies(e->assertThat(e.ingredientIds()).containsExactly("lentils"));
        var revised=planning.get(MEMBER,p.planId);assertThat(revised.version).isEqualTo(2);assertThat(revised.state).isEqualTo(com.nutrimarket.nutrimarket.model.planning.PlanningModels.State.DRAFT);
        assertThat(revised.meals).allSatisfy(m->assertThat(m.ingredients()).noneMatch(i->i.ingredientId().equals("lentils")));
        assertThat(revised.replans).singleElement().satisfies(r->assertThat(r.reason()).isEqualTo(ReplanReason.PREFERENCE));
        assertThat(planning.dislikes(MEMBER)).singleElement().satisfies(f->{assertThat(f.ingredientId()).isEqualTo("lentils");assertThat(f.name()).isNotBlank();});
        // Later plans leave the food out of the ingredients offered to the planner and of the market substitutes.
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(candidate("rice"));
        var next=planning.generate(MEMBER,new Generate(UUID.fromString(a.assessmentId),WEEK.plusWeeks(1),true));
        assertThat(next.excludedIngredients).contains("lentils");assertThat(next.audit.get(0).detail()).contains("Foods you dislike are left out");
        assertThatThrownBy(()->planning.dislike(MEMBER,List.of("not_a_food"))).isInstanceOf(AgentException.class);
        assertThat(planning.undislike(MEMBER,"lentils")).isEmpty();
    }
    @Test void dislikedFoodWithNoReplansLeftIsRememberedWithoutExpertEscalation(){
        product(Map.of("sku","rice","price",n("2")));var lentils=product(Map.of("sku","lentils","ingredientId","lentils","ingredients",List.of("Brown lentils (100%)"),"price",n("2")));
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(withLentils());
        var p=publish(generate(assessment(List.of())));var plan=planning.get(MEMBER,p.planId);plan.replanCount=2;mapper.update(plan,json.writeValueAsString(plan));
        var b=budget(start(p),"100");
        var changed=procurement.change(MEMBER,b.basketId,new Change(b.version,edited(b,new ItemEdit(UUID.fromString(lentils.productId()),ItemChoice.DISLIKE_FOOD)),true));
        assertThat(changed.state).isEqualTo(ProcurementModels.State.DRAFT);assertThat(changed.replanStatus).isNull();
        assertThat(changed.audit.get(changed.audit.size()-1)).satisfies(e->{assertThat(e.action()).isEqualTo("FOOD_DISLIKE_SAVED");assertThat(e.detail()).contains("used all of its replans");});
        // The menu is unchanged, so the basket still buys the planned food for those meals.
        assertThat(changed.evaluation.lines()).anyMatch(l->l.product().productId().equals(lentils.productId()));
        assertThat(planning.get(MEMBER,p.planId).version).isEqualTo(1);assertThat(planning.dislikes(MEMBER)).hasSize(1);
    }
    @Test void dislikedProductsStayOutOfLaterBasketsAsSoftDefaults(){
        var cheap=product(Map.of("sku","cheap","price",n("2")));product(Map.of("sku","alternative","price",n("2.5")));
        var a=assessment(List.of());var first=budget(start(publish(generate(a))),"100");
        var edited=procurement.change(MEMBER,first.basketId,new Change(first.version,edited(first,new ItemEdit(UUID.fromString(cheap.productId()),ItemChoice.DISLIKE))));
        assertThat(edited.evaluation.lines()).noneMatch(l->l.product().facts().sku().equals("cheap"));
        var next=start(publish(planning.generate(MEMBER,new Generate(UUID.fromString(a.assessmentId),WEEK.plusWeeks(1),true))));
        assertThat(next.settings.exclusionList()).singleElement().satisfies(e->{assertThat(e.value()).isEqualTo("cheap");assertThat(e.wasLearned()).isTrue();});
        assertThat(next.evaluation.lines()).isNotEmpty().noneMatch(l->l.product().facts().sku().equals("cheap"));
        assertThat(next.learnedSignals).anyMatch(x->x.contains("products you disliked cheap"));
    }
    @Test void editSavedAfterTheCatalogueChangedRechecksEvidenceInsteadOfFailingSilently(){
        var cheap=product(Map.of("sku","cheap","price",n("2")));var alternative=product(Map.of("sku","alternative","price",n("2.5")));
        var b=budget(start(published()),"100");
        // A catalogue re-stamp after the page loaded: the market result and the basket become stale, and the basket version moves on.
        catalog.save(EXPERT,alternative.productId(),change(facts(),Map.of("sku","alternative","price",n("2.5"),"expectedVersion",alternative.version())));
        var stale=procurement.get(MEMBER,b.basketId);assertThat(stale.state).isEqualTo(ProcurementModels.State.STALE);assertThat(stale.version).isEqualTo(b.version+1);
        var changed=procurement.change(MEMBER,b.basketId,new Change(b.version,edited(b,new ItemEdit(UUID.fromString(cheap.productId()),ItemChoice.DISLIKE))));
        assertThat(changed.state).isEqualTo(ProcurementModels.State.DRAFT);
        assertThat(changed.evaluation.lines()).singleElement().satisfies(l->{assertThat(l.product().productId()).isEqualTo(alternative.productId());assertThat(l.product().version()).isEqualTo(2);});
        assertThat(market.get(MEMBER,b.resultId).state).isEqualTo(State.COMPLETED);
        // A real change by the member in between is still a conflict.
        assertThatThrownBy(()->procurement.change(MEMBER,b.basketId,new Change(b.version,edited(b)))).isInstanceOf(AgentException.class);
    }
    @Test void homeDeliveryNeedsASavedAddressThatOnlyTheStoreOrderReceives()throws Exception{
        var b=basket();b=procurement.change(MEMBER,b.basketId,new Change(b.version,new Settings(n("100"),"AUD",Preference.ECONOMY,Fulfilment.DELIVERY,List.of(),List.of())));
        var version=b.version;var id=b.basketId;
        assertThatThrownBy(()->procurement.submit(MEMBER,id,new Checkout(version,UUID.randomUUID()))).isInstanceOf(AgentException.class).hasMessageContaining("delivery address");
        assertThat(request("GET","/address",null,token).body()).contains("\"address\":null");
        var address=new HashMap<String,Object>(Map.of("recipient","Alex Member","line1","12 Example St","suburb","Camperdown","state","NSW","postcode","20500"));
        assertThat(request("PUT","/address",address,token).statusCode()).isEqualTo(400);
        address.put("postcode","2050");address.put("instructions","Leave at the front desk");
        assertThat(request("PUT","/address",address,token).statusCode()).isEqualTo(200);assertThat(request("GET","/address",null,otherToken).body()).contains("\"address\":null");
        var placed=procurement.submit(MEMBER,id,new Checkout(version,UUID.randomUUID())).order();
        assertThat(placed.deliveryAddress.postcode()).isEqualTo("2050");
        assertThat(placed.storeOrders).isNotEmpty().allSatisfy(o->assertThat(o.fulfilmentDetail()).contains("from Store A, 1 Test Street, Sydney NSW 2000 to Alex Member, 12 Example St, Camperdown NSW 2050","Leave at the front desk"));
        assertThat(procurement.get(MEMBER,id).evaluation.fees()).allSatisfy(f->assertThat(f.address()).isEqualTo("1 Test Street, Sydney NSW 2000"));
        // A later address change does not rewrite the placed order.
        address.put("line1","99 Other Rd");request("PUT","/address",address,token);
        assertThat(procurement.order(MEMBER,placed.orderId).deliveryAddress.line1()).isEqualTo("12 Example St");
    }
    @Test void notNeededOmitsTheIngredientWithoutReplacementOrInventedPantryStock(){
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(mixed());
        var rice=product(Map.of("sku","rice"));product(Map.of("sku","lentils","ingredientId","lentils","ingredients",List.of("Brown lentils (100%)")));
        var b=budget(start(published()),"100");var plan=b.source.originalPlanningPackage();
        var next=procurement.change(MEMBER,b.basketId,new Change(b.version,edited(b,new ItemEdit(UUID.fromString(rice.productId()),ItemChoice.NOT_NEEDED))));
        assertThat(next.settings.omittedIngredients()).containsExactly("rice");assertThat(next.evaluation.lines()).singleElement().satisfies(l->{
            assertThat(l.product().facts().ingredientId()).isEqualTo("lentils");assertThat(l.fromPantry()).isZero();
            assertThat(l.checks()).anySatisfy(c->{assertThat(c.constraint()).isEqualTo("Daily nutrition targets");assertThat(c.result()).isEqualTo("CONTEXT");});});
        assertThat(next.evaluation.total()).isEqualByComparingTo("15");assertThat(next.source.originalPlanningPackage()).isEqualTo(plan);
        assertThat(next.evaluation.unfulfilledMenuLines()).isEmpty();assertThat(pantry.view(MEMBER).stock()).isEmpty();
        var order=submit(next).order();assertThat(order.selectedSkus).containsExactly("lentils");
    }
    @Test void removingEveryIngredientLeavesNothingToOrderAndUnknownProductsAreRejected()throws Exception {
        var b=budget(basket(),"100");var id=UUID.fromString(b.evaluation.lines().get(0).product().productId());
        assertThat(request("PUT","/baskets/"+b.basketId+"/preferences",new Change(b.version,edited(b,new ItemEdit(UUID.randomUUID(),ItemChoice.NOT_NEEDED))),token).statusCode()).isEqualTo(422);
        var next=procurement.change(MEMBER,b.basketId,new Change(b.version,edited(b,new ItemEdit(id,ItemChoice.NOT_NEEDED))));
        assertThat(next.evaluation.lines()).isEmpty();assertThat(next.evaluation.total()).isZero();
        assertThatThrownBy(()->submit(next)).isInstanceOf(AgentException.class).hasMessageContaining("no products");assertThat(orders()).isZero();
    }
    @Test void sixOptionsCompareActualFeesAndProductRangesWithoutSavingOrOrdering()throws Exception {
        product(Map.of("sku","basic","price",n("2"),"qualityTier","BASIC"));product(Map.of("sku","standard","price",n("2.1"),"qualityTier","STANDARD"));product(Map.of("sku","premium","price",n("3"),"qualityTier","PREMIUM"));
        var b=budget(start(published()),"100");var before=json.writeValueAsString(procurement.get(MEMBER,b.basketId));
        var response=request("POST","/baskets/"+b.basketId+"/compare",new Change(b.version,edited(b)),token);assertThat(response.statusCode()).isEqualTo(200);
        var comparison=json.readValue(response.body(),BasketComparison.class);assertThat(comparison.options()).hasSize(6);
        for(var mode:Fulfilment.values()){
            var options=comparison.options().stream().filter(o->o.fulfilment()==mode).toList();
            var economy=options.get(0);var balanced=options.get(1);var premium=options.get(2);
            assertThat(economy.total()).isLessThan(balanced.total());assertThat(balanced.total()).isLessThan(premium.total());
            assertThat(balanced.total()).isLessThanOrEqualTo(economy.total().multiply(n("1.15")));
            assertThat(premium.products()).allSatisfy(p->assertThat(p.qualityTier()).isEqualTo(QualityTier.PREMIUM));
            assertThat(economy.feeTotal()).isEqualByComparingTo(mode==Fulfilment.DELIVERY?"8":"0");
        }
        assertThat(json.writeValueAsString(procurement.get(MEMBER,b.basketId))).isEqualTo(before);assertThat(orders()).isZero();
        assertThat(request("POST","/baskets/"+b.basketId+"/compare",new Change(b.version,edited(b)),otherToken).statusCode()).isEqualTo(404);
        assertThat(request("POST","/baskets/"+b.basketId+"/compare",new Change(b.version+1,edited(b)),token).statusCode()).isEqualTo(409);
    }
    @Test void basketKeepsThePlannedFoodEvenWhenAPlannerSubstituteIsCheaper(){
        product(Map.of("price",n("9")));product(Map.of("sku","WHITE-RICE","ingredientId","white_rice","ingredients",List.of("Long grain white rice (100%)"),"price",n("1")));
        var b=budget(basket(),"500");assertThat(b.evaluation.lines()).allSatisfy(l->assertThat(l.product().facts().ingredientId()).isEqualTo("rice"));}
    @Test void newBasketOpensOnACompleteDraftWithASuggestedBudget(){
        var b=basket();assertThat(b.state).isEqualTo(ProcurementModels.State.DRAFT);assertThat(b.evaluation.issues()).isEmpty();
        assertThat(b.settings.budget()).isGreaterThanOrEqualTo(b.evaluation.total());assertThat(b.settings.budget().remainder(n("5"))).isEqualByComparingTo("0");
        assertThat(b.defaultChoice).contains("Budget suggested");}
    @Test void untouchedDeliveryBasketSwitchesToPickupWhenNoSingleStoreCanSupplyIt(){
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(mixed());
        product(Map.of("sku","rice","price",n("2")));product(Map.of("sku","lentils","store","Store B","ingredientId","lentils","ingredients",List.of("Brown lentils (100%)"),"price",n("2")));
        // A basket created before this rule, still on its delivery default and never edited by the member.
        var b=start(published());b.settings=new Settings(n("100"),"AUD",Preference.BALANCED,Fulfilment.DELIVERY,List.of(),List.of());b.state=ProcurementModels.State.BLOCKED;b.defaultChoice=null;
        jdbc.update("UPDATE shopping_basket SET state='BLOCKED',document=? WHERE basket_id=?",json.writeValueAsString(b),b.basketId);
        var opened=procurement.get(MEMBER,b.basketId);
        assertThat(opened.state).isEqualTo(ProcurementModels.State.DRAFT);assertThat(opened.settings.fulfilment()).isEqualTo(Fulfilment.PICKUP);
        assertThat(opened.settings.budget()).isEqualByComparingTo("100");assertThat(opened.defaultChoice).contains("pick-up");
        assertThat(opened.audit).anyMatch(e->e.action().equals("DEFAULT_CHOSEN"));}
    @Test void overBudgetBasketStaysBlockedButShowsTheLowestCompleteTotal(){
        var b=budget(basket(),"5");assertThat(b.state).isEqualTo(ProcurementModels.State.BLOCKED);
        assertThat(b.minimumTotal).isGreaterThan(n("5"));assertThat(b.minimumChoice).contains("Economy");
        assertThat(budget(b,"1000").minimumTotal).isNull();}
    @Test void deliveryNeedsOneStoreWhilePickupCanCombineCheaperProducts(){
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(mixed());
        product(Map.of("sku","rice","price",n("2")));product(Map.of("sku","lentils","store","Store B","ingredientId","lentils","ingredients",List.of("Brown lentils (100%)"),"price",n("2")));
        var b=budget(start(published()),"100");var compared=procurement.compare(MEMBER,b.basketId,new Change(b.version,edited(b)));
        assertThat(compared.options()).filteredOn(o->o.fulfilment()==Fulfilment.PICKUP).allSatisfy(o->{assertThat(o.available()).isTrue();assertThat(o.stores()).hasSize(2);assertThat(o.total()).isEqualByComparingTo("20");});
        assertThat(compared.options()).filteredOn(o->o.fulfilment()==Fulfilment.DELIVERY).allSatisfy(o->{assertThat(o.available()).isFalse();assertThat(o.issues()).anyMatch(i->i.contains("single supermarket"));});
    }
    @Test void budgetBlocksConfirmationAndNoClientTotalIsAccepted(){var b=budget(basket(),"20");assertThat(b.state).isEqualTo(ProcurementModels.State.BLOCKED);var checked=checkout(b);assertThat(checked.state).isEqualTo(ProcurementModels.State.BLOCKED);assertThatThrownBy(()->procurement.confirm(MEMBER,b.basketId,confirm(checked))).isInstanceOf(AgentException.class);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM procurement_order",Integer.class)).isZero();}
    @Test void checkoutRefreshThenLocalConfirmationIsIdempotentAndCancellable(){var b=budget(basket(),"100");var request=new Checkout(b.version,UUID.randomUUID());var checked=procurement.checkout(MEMBER,b.basketId,request);assertThat(checked.state).isEqualTo(ProcurementModels.State.CHECKOUT_READY);assertThat(checked.marketRevision).isEqualTo(2);assertThat(procurement.checkout(MEMBER,b.basketId,request).version).isEqualTo(checked.version);var confirmation=confirm(checked);var order=procurement.confirm(MEMBER,b.basketId,confirmation);assertThat(procurement.confirm(MEMBER,b.basketId,confirmation).orderId).isEqualTo(order.orderId);assertThat(order.status).isEqualTo("CONFIRMED");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM procurement_order",Integer.class)).isEqualTo(1);assertThat(procurement.cancel(MEMBER,order.orderId).status).isEqualTo("CANCELLED");assertThat(procurement.cancel(MEMBER,order.orderId).status).isEqualTo("CANCELLED");}
    @Test void requiresFreshCheckoutAndExplicitConsent(){var b=budget(basket(),"100");assertThatThrownBy(()->procurement.confirm(MEMBER,b.basketId,confirm(b))).isInstanceOf(AgentException.class);var checked=checkout(b);assertThatThrownBy(()->procurement.confirm(MEMBER,b.basketId,new Confirm(checked.version,UUID.randomUUID(),false))).isInstanceOf(AgentException.class);when(clock.instant()).thenReturn(NOW.plusSeconds(301));assertThatThrownBy(()->procurement.confirm(MEMBER,b.basketId,confirm(checked))).isInstanceOf(AgentException.class);assertThat(procurement.get(MEMBER,b.basketId).state).isEqualTo(ProcurementModels.State.STALE);}
    @Test void changedPriceNeedsAnotherRefreshAndConfirmation(){var b=checkout(budget(basket(),"100"));var line=b.evaluation.lines().get(0);catalog.save(EXPERT,line.product().productId(),change(line.product().facts(),Map.of("expectedVersion",1,"price",n("4"))));assertThatThrownBy(()->procurement.confirm(MEMBER,b.basketId,confirm(b))).isInstanceOf(AgentException.class);var stale=procurement.get(MEMBER,b.basketId);assertThat(stale.state).isEqualTo(ProcurementModels.State.STALE);var checked=checkout(stale);assertThat(checked.evaluation.total()).isEqualByComparingTo("36");assertThat(checked.state).isEqualTo(ProcurementModels.State.CHECKOUT_READY);assertThatThrownBy(()->procurement.confirm(MEMBER,b.basketId,confirm(b))).isInstanceOf(AgentException.class);}
    @Test void profileChangeBlocksOrderAndPreservesHistoricalConfirmedSnapshot(){var b=checkout(budget(basket(),"100"));var order=procurement.confirm(MEMBER,b.basketId,confirm(b));health.saveProfile(MEMBER,input(1,List.of("milk")));assertThat(procurement.order(MEMBER,order.orderId).snapshot.profileVersion).isEqualTo(1);assertThat(procurement.get(MEMBER,b.basketId).state).isEqualTo(ProcurementModels.State.ORDERED);}
    @Test void profileChangeBeforeConfirmationBlocksOrder(){var b=checkout(budget(basket(),"100"));health.saveProfile(MEMBER,input(1,List.of("milk")));assertThatThrownBy(()->procurement.confirm(MEMBER,b.basketId,confirm(b))).isInstanceOf(AgentException.class);}
    @Test void exclusionsCannotWaiveConstraintsAndPreferenceEditsResetCheckout(){var b=checkout(budget(basket(),"100"));var s=new Settings(n("100"),"AUD",Preference.ECONOMY,Fulfilment.DELIVERY,List.of(),List.of(UUID.fromString(b.evaluation.lines().get(0).product().productId())));var changed=procurement.change(MEMBER,b.basketId,new Change(b.version,s));assertThat(changed.state).isEqualTo(ProcurementModels.State.BLOCKED);assertThat(changed.checkoutAt).isNull();assertThat(changed.evaluation.lines()).isEmpty();assertThatThrownBy(()->procurement.replan(MEMBER,b.basketId,new ReplanInput(changed.version,UUID.randomUUID(),true))).isInstanceOf(AgentException.class).hasMessageContaining("no permitted product");}
    @Test void unknownAllergensAreNeverRankedEvenWhenCheap(){product(Map.of("allergensComplete",false,"price",n("0.01")));var r=match(published());var b=procurement.start(MEMBER,new ProcurementModels.Start(UUID.fromString(r.resultId)));assertThat(b.evaluation.lines()).isEmpty();assertThat(b.evaluation.unfulfilledMenuLines()).hasSize(21);}
    @Test void rawYieldUsesSoldWeightAndCorrectPackageCount(){var raw=new TreeMap<String,Nutrient>();nutrients().forEach((k,v)->raw.put(k,new Nutrient(v.amount().multiply(n("2.5")),v.unit())));product(Map.of("preparation","RAW","cookedYieldFactor",n("2.5"),"per100g",raw));var b=budget(procurement.start(MEMBER,new ProcurementModels.Start(UUID.fromString(match(published()).resultId))),"100");assertThat(b.evaluation.lines().get(0).requiredSoldGrams()).isEqualByComparingTo("1680");assertThat(b.evaluation.lines().get(0).packages()).isEqualByComparingTo("4");assertThat(b.evaluation.total()).isEqualByComparingTo("12");}
    @Test void promotionsNeverBeatCheaperEquivalentProducts(){product(Map.of("sku","promoted","price",n("5"),"promotion","Special offer"));var cheap=product(Map.of("sku","plain","price",n("2")));var b=budget(procurement.start(MEMBER,new ProcurementModels.Start(UUID.fromString(match(published()).resultId))),"100");assertThat(b.evaluation.total()).isEqualByComparingTo("18");assertThat(b.evaluation.lines()).singleElement().satisfies(l->assertThat(l.product().productId()).isEqualTo(cheap.productId()));}
    @Test void apiRequiresOwnershipAndValidInputs()throws Exception{var b=basket();assertThat(request("GET","/baskets",null,null).statusCode()).isEqualTo(401);assertThat(request("GET","/baskets/"+b.basketId,null,otherToken).statusCode()).isEqualTo(404);assertThat(request("POST","/baskets/"+b.basketId+"/confirm",new Confirm(1,UUID.randomUUID(),false),token).statusCode()).isEqualTo(400);assertThat(request("GET","/expert/queue",null,otherToken).statusCode()).isEqualTo(403);assertThat(request("GET","/reports",null,otherToken).statusCode()).isEqualTo(403);assertThat(request("GET","/baskets?page=0",null,token).statusCode()).isEqualTo(400);}
    @Test void localHandoffCreatesExactlyOneBasket(){product(Map.of());var r=match(published());var delivery=new MarketDelivery(markets,market,new com.nutrimarket.nutrimarket.config.MarketPolicy("market-v1",com.nutrimarket.nutrimarket.config.MarketPolicy.Source.LOCAL,"","",Duration.ofSeconds(1),Duration.ofHours(24),3,20,true,"qwen-plus",Duration.ofSeconds(1),.85,List.of(),"","",Duration.ofSeconds(1),3),procurement);delivery.deliverPending();delivery.deliverPending();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopping_basket",Integer.class)).isEqualTo(1);assertThat(markets.delivery(r.resultId,1).state()).isEqualTo("DELIVERED");}
    @Test void checkoutRefreshesKeepTheManualMarketBudgetAndAreBoundedPerBasket(){
        var b=budget(basket(),"100");for(int i=0;i<5;i++){b=checkout(b);assertThat(b.state).isEqualTo(ProcurementModels.State.CHECKOUT_READY);}
        var r=market.get(MEMBER,b.resultId);assertThat(r.refreshCount).isZero();assertThat(r.preOrderRefreshCount).isEqualTo(5);
        var limited=b;assertThatThrownBy(()->checkout(limited)).isInstanceOf(AgentException.class).hasMessageContaining("checkout refresh limit");
        var queued=procurement.escalate(MEMBER,b.basketId,new ProcurementModels.Version(b.version));
        var returned=procurement.review(EXPERT,b.basketId,new ProcurementModels.Review(queued.version,ProcurementModels.Decision.RETURN_FOR_ADJUSTMENT,"Evidence checked; allow a fresh checkout."));
        assertThat(checkout(returned).state).isEqualTo(ProcurementModels.State.CHECKOUT_READY);
    }
    static Candidate mixed(){List<CandidateMeal> meals=new ArrayList<>();for(int day=0;day<7;day++)for(var slot:Slot.values())meals.add(new CandidateMeal(day,slot,List.of(new Portion("rice",n("100")),new Portion("lentils",n("100")))));return new Candidate(meals);}
    Settings settings(String budget,Fulfilment fulfilment){return new Settings(n(budget),"AUD",Preference.ECONOMY,fulfilment,List.of(),List.of());}
    Basket start(Plan p){return procurement.start(MEMBER,new ProcurementModels.Start(UUID.fromString(match(p).resultId)));}
    @Test void economyNeverPaysExtraForPromotionsWhileBalancedCanBreakNearTies(){var promoted=product(Map.of("sku","promoted","price",n("2.02"),"promotion","Member price"));product(Map.of("sku","plain","price",n("2")));
        var b=budget(start(published()),"100");assertThat(b.evaluation.total()).isEqualByComparingTo("18");
        var balanced=procurement.change(MEMBER,b.basketId,new Change(b.version,with("100",Preference.BALANCED)));
        assertThat(balanced.evaluation.lines()).singleElement().satisfies(l->assertThat(l.product().productId()).isEqualTo(promoted.productId()));
        assertThat(balanced.evaluation.total()).isEqualByComparingTo("18.18");assertThat(balanced.evaluation.promotionDecision()).contains("near-equivalence");}
    @Test void splittingStoresMustSaveMoreThanTheExtraDeliveryFee(){
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(mixed());
        product(Map.of("sku","A-rice","price",n("2")));product(Map.of("sku","A-lentils","ingredientId","lentils","ingredients",List.of("Brown lentils (100%)"),"price",n("3")));
        product(Map.of("sku","B-rice","store","Store B","price",n("3")));product(Map.of("sku","B-lentils","store","Store B","ingredientId","lentils","ingredients",List.of("Brown lentils (100%)"),"price",n("2")));
        var b=start(published());var pickup=procurement.change(MEMBER,b.basketId,new Change(b.version,settings("100",Fulfilment.PICKUP)));
        assertThat(pickup.evaluation.lines()).extracting(l->l.product().facts().store()).containsExactlyInAnyOrder("Store A","Store B");assertThat(pickup.evaluation.total()).isEqualByComparingTo("20");
        var delivery=procurement.change(MEMBER,b.basketId,new Change(pickup.version,settings("100",Fulfilment.DELIVERY)));
        assertThat(delivery.evaluation.lines().stream().map(l->l.product().facts().store()).distinct()).hasSize(1);
        assertThat(delivery.evaluation.productTotal()).isEqualByComparingTo("25");assertThat(delivery.evaluation.total()).isEqualByComparingTo("33");
        assertThat(delivery.evaluation.fees()).singleElement().satisfies(f->{assertThat(f.quoted()).isTrue();assertThat(f.amount()).isEqualByComparingTo("8");});
    }
    @Test void unconfiguredDeliveryFeesStayUnquoted(){product(Map.of("store","Store C"));var b=start(published());var d=procurement.change(MEMBER,b.basketId,new Change(b.version,settings("100",Fulfilment.DELIVERY)));
        assertThat(d.evaluation.fees()).singleElement().satisfies(f->{assertThat(f.quoted()).isFalse();assertThat(f.amount()).isNull();});
        assertThat(d.evaluation.total()).isEqualByComparingTo(d.evaluation.productTotal());assertThat(d.evaluation.explanation()).contains("No delivery fee is configured for Store C");
        assertThat(d.state).isEqualTo(ProcurementModels.State.BLOCKED);
        var comparison=procurement.compare(MEMBER,d.basketId,new Change(d.version,settings("100",Fulfilment.DELIVERY)));
        assertThat(comparison.options()).filteredOn(o->o.fulfilment()==Fulfilment.DELIVERY).allSatisfy(o->{assertThat(o.available()).isFalse();assertThat(o.total()).isNull();assertThat(o.feeTotal()).isNull();});}
    @Test void checkoutRefreshCarriesPlanBasketAndFreshnessContract(){var b=checkout(budget(basket(),"100"));
        assertThat(market.get(MEMBER,b.resultId).refreshes).last().satisfies(a->{assertThat(a.basketId()).isEqualTo(b.basketId);assertThat(a.requiredFreshAfter()).isEqualTo(NOW.minus(Duration.ofHours(24)));});}
    @Test void standingConsentLetsAgent5IssueTheReplanRequest(){product(Map.of("availability",Availability.OUT_OF_STOCK));var p=published();var b=start(p);
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(candidate("lentils"));
        var auto=procurement.change(MEMBER,b.basketId,new Change(b.version,new Settings(n("100"),"AUD",Preference.ECONOMY,Fulfilment.PICKUP,List.of(),List.of(),true)));
        assertThat(auto.state).isEqualTo(ProcurementModels.State.STALE);assertThat(auto.audit).anyMatch(e->e.action().equals("AUTO_REPLAN_REQUESTED"));
        var plan=planning.get(MEMBER,p.planId);assertThat(plan.version).isEqualTo(2);
        assertThat(plan.replans).singleElement().satisfies(a->{assertThat(a.attempt()).isEqualTo(1);assertThat(a.evidence()).contains("UNAVAILABLE");});}
    @Test void withoutStandingConsentInfeasibleBasketsWaitForTheMember(){product(Map.of("availability",Availability.OUT_OF_STOCK));var p=published();
        var blocked=budget(start(p),"100");assertThat(blocked.state).isEqualTo(ProcurementModels.State.BLOCKED);assertThat(planning.get(MEMBER,p.planId).version).isEqualTo(1);}
    @Test void reviewCannotApproveOrOverrideConstraints(){var b=basket();var queued=procurement.escalate(MEMBER,b.basketId,new ProcurementModels.Version(b.version));assertThat(procurement.queue(EXPERT,1,10).items()).hasSize(1);assertThatThrownBy(()->procurement.review(MEMBER,b.basketId,new ProcurementModels.Review(queued.version,ProcurementModels.Decision.RETURN_FOR_ADJUSTMENT,"Checked"))).isInstanceOf(AgentException.class);var returned=procurement.review(EXPERT,b.basketId,new ProcurementModels.Review(queued.version,ProcurementModels.Decision.RETURN_FOR_ADJUSTMENT,"Update evidence and refresh."));assertThat(returned.state).isEqualTo(ProcurementModels.State.STALE);assertThatThrownBy(()->procurement.confirm(MEMBER,b.basketId,confirm(returned))).isInstanceOf(AgentException.class);}
    @Test void localReportsSuppressSmallCohortsAndContainNoHealthData(){var b=checkout(budget(basket(),"100"));procurement.confirm(MEMBER,b.basketId,confirm(b));var report=procurement.report(EXPERT);assertThat(report.suppressed()).isTrue();assertThat(report.demand()).isEmpty();assertThat(json.writeValueAsString(report)).doesNotContain(MEMBER,"assessmentId","profileVersion");}
    @Test void concurrentConfirmationsCannotCreateTwoOrders()throws Exception{var b=checkout(budget(basket(),"100"));var input=confirm(b);var pool=Executors.newFixedThreadPool(2);try{var one=pool.submit(()->procurement.confirm(MEMBER,b.basketId,input));var two=pool.submit(()->procurement.confirm(MEMBER,b.basketId,input));assertThat(one.get(10,TimeUnit.SECONDS).orderId).isEqualTo(two.get(10,TimeUnit.SECONDS).orderId);}finally{pool.shutdownNow();}}
    @Test void currentOutOfStockEvidenceCanRequestBoundedReplanning(){product(Map.of("availability",Availability.OUT_OF_STOCK));var p=published();var b=procurement.start(MEMBER,new ProcurementModels.Start(UUID.fromString(match(p).resultId)));when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(candidate("lentils"));var revised=procurement.replan(MEMBER,b.basketId,new ReplanInput(b.version,UUID.randomUUID(),true));assertThat(revised.version).isEqualTo(2);assertThat(revised.replans.get(0).evidence()).contains("Market result", "UNAVAILABLE");assertThat(procurement.get(MEMBER,b.basketId).state).isEqualTo(ProcurementModels.State.STALE);}
    @Test void replanLimitEscalatesTheBasketNotThePlan(){product(Map.of("availability",Availability.OUT_OF_STOCK));var p=published();
        var plan=planning.get(MEMBER,p.planId);plan.replanCount=2;mapper.update(plan,json.writeValueAsString(plan));var b=start(p);
        assertThatThrownBy(()->procurement.replan(MEMBER,b.basketId,new ReplanInput(b.version,UUID.randomUUID(),true))).isInstanceOf(AgentException.class).hasMessageContaining("Human Expert");
        var escalated=procurement.get(MEMBER,b.basketId);assertThat(escalated.state).isEqualTo(ProcurementModels.State.REVIEW_REQUIRED);assertThat(escalated.replanStatus).isEqualTo("ESCALATED");
        assertThat(procurement.queue(EXPERT,1,10).items()).singleElement().satisfies(q->assertThat(q.basketId).isEqualTo(b.basketId));
        var unchanged=planning.get(MEMBER,p.planId);assertThat(unchanged.state).isEqualTo(com.nutrimarket.nutrimarket.model.planning.PlanningModels.State.PUBLISHED);assertThat(unchanged.version).isEqualTo(1);}

    @Test void dailyTargetsAreRecheckedBeyondIndividualProductTolerances(){
        var rich=new TreeMap<>(nutrients());rich.put("1008",new Nutrient(n("110"),"kcal"));product(Map.of("per100g",rich));var r=match(published());var original=market.packageFor(MEMBER,r.resultId);
        // Emulate a future planner with wider per-ingredient tolerances; daily targets must still hold.
        var p=original.originalPlanningPackage();var groups=p.substitutionGroups().stream().map(g->{var tolerances=new TreeMap<>(g.nutrientTolerances());tolerances.put("1008",new Tolerance(n("100"),n("300"),"kcal"));return new SubstitutionGroup(g.substitutionGroupId(),g.menuLineId(),g.allowedIngredientIds(),g.quantity(),g.unit(),tolerances,g.prohibitedSubstitutions());}).toList();
        var plan=new PlanningPackage(p.schemaVersion(),p.planId(),p.planVersion(),p.memberId(),p.assessmentId(),p.profileVersion(),p.assessmentRevision(),p.policyVersion(),p.weekStart(),p.weekEnd(),p.weeklyMealPlan(),p.ingredientRequirements(),groups,p.nutritionTargets(),p.healthConstraintSet(),p.dailyNutrition(),p.nutritionSources(),p.publishedAt());
        var results=original.marketIntelligenceResult().stream().map(q->new RequirementResult(q.requirementId(),q.menuLineId(),q.ingredientId(),q.requiredGrams(),Outcome.MATCHED,List.of(),q.candidates().stream().map(m->new ProductMatch(m.product(),m.confidence(),m.matchingMethod(),Compliance.COMPLIANT,true,Outcome.MATCHED,List.of(),m.unitPricePer100g(),m.packagesToCover(),m.costToCover())).toList(),q.checkedAt(),false)).toList();
        var pack=new ProcurementInputPackage(1,original.resultId(),1,p.planId(),p.planVersion(),p.assessmentId(),p.profileVersion(),p.weeklyMealPlan(),groups,plan,results,true,false,NOW);
        var evaluation=baskets.evaluate(pack,new Settings(n("100"),"AUD",Preference.ECONOMY,Fulfilment.PICKUP,List.of(),List.of()));assertThat(evaluation.lines()).isEmpty();assertThat(evaluation.issues().get(0)).contains("daily nutrition");
    }
    @Test void preferredStoresCannotOverrideBudget(){product(Map.of("store","Preferred","sku","preferred","price",n("10")));product(Map.of("store","Other","sku","other","price",n("2")));var b=procurement.start(MEMBER,new ProcurementModels.Start(UUID.fromString(match(published()).resultId)));var adjusted=procurement.change(MEMBER,b.basketId,new Change(b.version,new Settings(n("20"),"AUD",Preference.ECONOMY,Fulfilment.PICKUP,List.of("Preferred"),List.of())));assertThat(adjusted.state).isEqualTo(ProcurementModels.State.DRAFT);assertThat(adjusted.evaluation.total()).isLessThanOrEqualTo(n("20"));}
    @Test void sourceFailureCannotBeConvertedIntoIngredientUnavailability(){product(Map.of());when(matcher.match(any(),anyList())).thenThrow(AgentException.provider("MARKET_MODEL_UNAVAILABLE","Unavailable"));var b=procurement.start(MEMBER,new ProcurementModels.Start(UUID.fromString(match(published()).resultId)));assertThatThrownBy(()->procurement.replan(MEMBER,b.basketId,new ReplanInput(b.version,UUID.randomUUID(),true))).isInstanceOf(AgentException.class).hasMessageContaining("complete current evidence");}
    @Test void checkoutRefreshDetectsUnavailableProducts(){var b=budget(basket(),"100");var p=b.evaluation.lines().get(0).product();catalog.save(EXPERT,p.productId(),change(p.facts(),Map.of("expectedVersion",1,"availability",Availability.OUT_OF_STOCK)));var result=checkout(b);assertThat(result.state).isEqualTo(ProcurementModels.State.BLOCKED);assertThat(result.evaluation.lines()).isEmpty();assertThatThrownBy(()->procurement.confirm(MEMBER,b.basketId,confirm(result))).isInstanceOf(AgentException.class);}
    @Test void reportRequiresFiveDistinctMembersForEachProduct(){
        product(Map.of());
        for(int i=0;i<5;i++){
            String member=UUID.randomUUID().toString();user(member,"cohort"+i);var profile=health.saveProfile(member,input(0,List.of()));
            var a=new Assessment();a.assessmentId=UUID.randomUUID().toString();a.memberId=member;a.profileVersion=profile.version();a.periodStart=WEEK.minusWeeks(2);a.periodEnd=a.periodStart.plusDays(6);a.timezone="Australia/Sydney";a.policyVersion="health-demo-v1";a.status=AssessmentStatus.COMPLETED;a.planningClearance=Clearance.CLEARED;a.riskStatus=Risk.WITHIN_TARGETS;a.createdAt=NOW;a.updatedAt=NOW;a.healthConstraintSet=new Constraints(List.of(),List.of(),List.of(),targets(),List.of());healthMapper.insertAssessment(a,json.writeValueAsString(a));
            var p=planning.generate(member,new Generate(UUID.fromString(a.assessmentId),WEEK,true));p=planning.publish(member,p.planId,new Publish(p.version,true));var r=market.start(member,new com.nutrimarket.nutrimarket.model.market.MarketModels.Start(UUID.fromString(p.planId),p.version));
            var b=procurement.start(member,new ProcurementModels.Start(UUID.fromString(r.resultId)));b=procurement.change(member,b.basketId,new Change(b.version,new Settings(n("100"),"AUD",Preference.ECONOMY,Fulfilment.PICKUP,List.of(),List.of())));b=procurement.checkout(member,b.basketId,new Checkout(b.version,UUID.randomUUID()));procurement.confirm(member,b.basketId,confirm(b));
        }
        var report=procurement.report(EXPERT);assertThat(report.suppressed()).isFalse();assertThat(report.demand()).singleElement().satisfies(d->{assertThat(d.packages()).isEqualByComparingTo("45");assertThat(d.value()).isEqualByComparingTo("135");});
    }

    SubmitResult submit(Basket b){return procurement.submit(MEMBER,b.basketId,new Checkout(b.version,UUID.randomUUID()));}
    Settings with(String budget,Preference preference,Exclusion... exclusions){return new Settings(n(budget),"AUD",preference,Fulfilment.PICKUP,List.of(),List.of(),false,List.of(exclusions));}
    long orders(){return jdbc.queryForObject("SELECT COUNT(*) FROM procurement_order",Long.class);}
    @Test void submissionWithoutMaterialChangeOrdersDirectlyAndOnlyOnce(){
        var b=budget(basket(),"100");var request=new Checkout(b.version,UUID.randomUUID());
        var result=procurement.submit(MEMBER,b.basketId,request);
        assertThat(result.basket().state).isEqualTo(ProcurementModels.State.ORDERED);assertThat(result.basket().materialChanges).isEmpty();
        var order=result.order();assertThat(order.status).isEqualTo("CONFIRMED");assertThat(order.mode).isEqualTo("SIMULATED_RETAILER");
        assertThat(order.storeOrders).singleElement().satisfies(s->{assertThat(s.status()).isEqualTo(StoreStatus.ACCEPTED);assertThat(s.retailerReference()).startsWith("SIM-");assertThat(s.fulfilmentDetail()).contains("Pickup at Store A");});
        assertThat(order.planId).isEqualTo(b.planId);assertThat(order.selectedSkus).containsExactly("RICE-01");assertThat(order.preferenceSignals.budget()).isEqualByComparingTo("100");
        assertThat(procurement.submit(MEMBER,b.basketId,request).order().orderId).isEqualTo(order.orderId);assertThat(orders()).isEqualTo(1);
        assertThatThrownBy(()->submit(procurement.get(MEMBER,b.basketId))).isInstanceOf(AgentException.class);
    }
    @Test void materialPriceChangeNeedsReconfirmationBeforeOrdering(){
        var b=budget(basket(),"100");var p=b.evaluation.lines().get(0).product();
        catalog.save(EXPERT,p.productId(),change(p.facts(),Map.of("expectedVersion",1,"price",n("3.5"))));
        var result=submit(procurement.get(MEMBER,b.basketId));assertThat(result.order()).isNull();var ready=result.basket();
        assertThat(ready.state).isEqualTo(ProcurementModels.State.CHECKOUT_READY);
        assertThat(ready.materialChanges).extracting(MaterialChange::type).containsExactly(ChangeType.PRICE,ChangeType.TOTAL);
        assertThat(ready.materialChanges.get(0).before()).contains("3");assertThat(ready.materialChanges.get(0).after()).contains("3.5");assertThat(orders()).isZero();
        assertThatThrownBy(()->procurement.confirm(MEMBER,b.basketId,new Confirm(ready.version,UUID.randomUUID(),false))).isInstanceOf(AgentException.class);
        var order=procurement.confirm(MEMBER,b.basketId,confirm(ready));assertThat(order.status).isEqualTo("CONFIRMED");assertThat(order.total).isEqualByComparingTo("31.5");
    }
    @Test void decliningMaterialChangesPlacesNoOrderAndReturnsToTheBasket(){
        var b=budget(basket(),"100");var p=b.evaluation.lines().get(0).product();
        catalog.save(EXPERT,p.productId(),change(p.facts(),Map.of("expectedVersion",1,"price",n("3.5"))));
        var ready=submit(procurement.get(MEMBER,b.basketId)).basket();
        var declined=procurement.decline(MEMBER,b.basketId,new ProcurementModels.Version(ready.version));
        assertThat(declined.state).isEqualTo(ProcurementModels.State.DRAFT);assertThat(declined.audit).anyMatch(e->e.action().equals("CHANGES_DECLINED"));assertThat(orders()).isZero();
        var again=submit(declined);assertThat(again.order()).isNotNull();assertThat(again.order().total).isEqualByComparingTo("31.5");
    }
    @Test void brandAndSkuExclusionsOnlyNarrowTheChoice(){
        product(Map.of("sku","plain","price",n("2")));product(Map.of("sku","other","brand","Other brand","price",n("2.5")));var b=start(published());
        var sku=procurement.change(MEMBER,b.basketId,new Change(b.version,with("100",Preference.ECONOMY,new Exclusion(ExclusionType.SKU,"plain"))));
        assertThat(sku.evaluation.lines()).singleElement().satisfies(l->assertThat(l.product().facts().sku()).isEqualTo("other"));
        var both=procurement.change(MEMBER,b.basketId,new Change(sku.version,with("100",Preference.ECONOMY,new Exclusion(ExclusionType.SKU,"plain"),new Exclusion(ExclusionType.BRAND,"other BRAND"))));
        assertThat(both.state).isEqualTo(ProcurementModels.State.BLOCKED);assertThat(both.evaluation.issues().get(0)).contains("your exclusions");
        assertThatThrownBy(()->procurement.change(MEMBER,b.basketId,new Change(both.version,with("100",Preference.ECONOMY,new Exclusion(ExclusionType.BRAND,"Unknown"))))).isInstanceOf(AgentException.class).hasMessageContaining("No product");
    }
    @Test void exclusionsNamingAHardConstraintAreRejectedWithTheReason()throws Exception{
        product(Map.of());var b=procurement.start(MEMBER,new ProcurementModels.Start(UUID.fromString(match(publish(generate(assessment(List.of("milk"))))).resultId)));
        assertThatThrownBy(()->procurement.change(MEMBER,b.basketId,new Change(b.version,with("100",Preference.ECONOMY,new Exclusion(ExclusionType.BRAND,"Milk")))))
            .isInstanceOf(AgentException.class).hasMessageContaining("allergy").hasMessageContaining("cannot add, remove or waive");
        var response=request("PUT","/baskets/"+b.basketId+"/preferences",new Change(b.version,with("100",Preference.ECONOMY,new Exclusion(ExclusionType.SKU,"Energy"))),token);
        assertThat(response.statusCode()).isEqualTo(422);assertThat(response.body()).contains("EXCLUSION_REJECTED","health red line");
    }
    @Test void balancedAndPremiumBuyHigherRecordedRangeTiersWithinTheirLimits(){
        product(Map.of("sku","basic","price",n("2"),"qualityTier","BASIC"));var premium=product(Map.of("sku","premium","brand","Premium brand","price",n("2.2"),"qualityTier","PREMIUM"));var b=start(published());
        var economy=procurement.change(MEMBER,b.basketId,new Change(b.version,with("100",Preference.ECONOMY)));assertThat(economy.evaluation.total()).isEqualByComparingTo("18");assertThat(economy.evaluation.qualityDecision()).isNull();
        var balanced=procurement.change(MEMBER,b.basketId,new Change(economy.version,with("100",Preference.BALANCED)));
        assertThat(balanced.evaluation.lines()).singleElement().satisfies(l->assertThat(l.product().productId()).isEqualTo(premium.productId()));
        assertThat(balanced.evaluation.total()).isEqualByComparingTo("19.8");assertThat(balanced.evaluation.qualityDecision()).contains("Balanced");
        var tight=procurement.change(MEMBER,b.basketId,new Change(balanced.version,with("19",Preference.PREMIUM)));
        assertThat(tight.state).isEqualTo(ProcurementModels.State.DRAFT);assertThat(tight.evaluation.total()).isLessThanOrEqualTo(n("19")).isGreaterThan(n("18"));
        assertThat(tight.evaluation.lines()).extracting(l->l.product().facts().sku()).containsExactlyInAnyOrder("basic","premium");
    }
    @Test void everyLineCarriesTraceableConstraintEvidence(){
        product(Map.of());var b=budget(procurement.start(MEMBER,new ProcurementModels.Start(UUID.fromString(match(publish(generate(assessment(List.of("milk"))))).resultId))),"100");
        assertThat(b.evaluation.lines().get(0).checks()).extracting(Check::constraint).contains("Allergy: milk","Substitution group","Nutrient tolerance","Daily nutrition targets","Availability and freshness","Source evidence");
        assertThat(b.evaluation.lines().get(0).allocations()).allSatisfy(a->assertThat(a.plannedIngredientId()).isEqualTo("rice"));
    }
    @Test void rejectedStoreOrderIsRecordedOnceAndRecoveryResubmits(){
        product(Map.of("store","Store B","sku","B-rice"));var b=budget(start(published()),"100");var request=new Checkout(b.version,UUID.randomUUID());
        var order=procurement.submit(MEMBER,b.basketId,request).order();
        assertThat(order.status).isEqualTo("REJECTED");assertThat(order.storeOrders).singleElement().satisfies(s->{assertThat(s.status()).isEqualTo(StoreStatus.REJECTED);assertThat(s.attempts()).isEqualTo(1);});
        assertThat(procurement.submit(MEMBER,b.basketId,request).order().status).isEqualTo("REJECTED");assertThat(orders()).isEqualTo(1);
        var action=new OrderAction(UUID.randomUUID());var recovered=procurement.recover(MEMBER,order.orderId,action);
        assertThat(recovered.status).isEqualTo("CONFIRMED");assertThat(recovered.storeOrders.get(0).attempts()).isEqualTo(2);
        assertThat(procurement.recover(MEMBER,order.orderId,action).storeOrders.get(0).attempts()).isEqualTo(2);
        assertThat(procurement.cancel(MEMBER,order.orderId).status).isEqualTo("CANCELLED");
    }
    @Test void partialFulfilmentCanBeReoptimisedWithoutTheRejectedSku(){
        when(model.propose(any(),anyString(),anyString(),anyMap(),any(),anyList(),anyList())).thenReturn(mixed());
        product(Map.of("store","Store D","sku","D-rice","price",n("2")));product(Map.of("store","Store D","sku","D-lentils","ingredientId","lentils","ingredients",List.of("Brown lentils (100%)"),"price",n("2")));
        product(Map.of("store","Store D","sku","D-lentils-large","ingredientId","lentils","ingredients",List.of("Brown lentils (100%)"),"price",n("3")));
        var b=budget(start(published()),"100");var order=submit(b).order();
        assertThat(order.status).isEqualTo("PARTIALLY_FULFILLED");assertThat(order.storeOrders.get(0).lines()).filteredOn(l->!l.accepted()).extracting(StoreLine::sku).containsExactly("D-lentils");
        assertThat(procurement.recover(MEMBER,order.orderId,new OrderAction(UUID.randomUUID())).status).isEqualTo("PARTIALLY_FULFILLED");
        var reopened=procurement.reoptimise(MEMBER,order.orderId,new OrderAction(UUID.randomUUID()));
        assertThat(reopened.state).isEqualTo(ProcurementModels.State.DRAFT);assertThat(reopened.settings.exclusionList()).extracting(Exclusion::value).containsExactly("D-lentils");
        assertThat(reopened.materialChanges).anySatisfy(c->{assertThat(c.type()).isEqualTo(ChangeType.SUBSTITUTION);assertThat(c.after()).contains("D-lentils-large");assertThat(c.detail()).contains("Out of stock at picking");});
        var resubmitted=submit(reopened).order();
        assertThat(resubmitted.orderId).isEqualTo(order.orderId);assertThat(resubmitted.status).isEqualTo("CONFIRMED");assertThat(resubmitted.submission).isEqualTo(2);assertThat(resubmitted.history).hasSize(1);assertThat(orders()).isEqualTo(1);
    }
    @Test void laterBasketsLearnSoftDefaultsFromAnAcceptedBasket(){
        product(Map.of("sku","plain","price",n("2")));product(Map.of("sku","other","brand","Other brand","price",n("2.5")));
        procurement.saveAddress(MEMBER,new DeliveryAddress("Alex Member","12 Example St",null,"Camperdown","NSW","2050",null));
        var a=assessment(List.of());var first=start(publish(generate(a)));first=procurement.change(MEMBER,first.basketId,new Change(first.version,new Settings(n("80"),"AUD",Preference.PREMIUM,Fulfilment.DELIVERY,List.of("Store A"),List.of(),true,List.of(new Exclusion(ExclusionType.SKU,"other")))));
        assertThat(submit(first).order().status).isEqualTo("CONFIRMED");
        var next=start(publish(planning.generate(MEMBER,new Generate(UUID.fromString(a.assessmentId),WEEK.plusWeeks(1),true))));
        assertThat(next.settings.budget()).isEqualByComparingTo("80");assertThat(next.settings.fulfilment()).isEqualTo(Fulfilment.DELIVERY);assertThat(next.settings.preference()).isEqualTo(Preference.PREMIUM);
        assertThat(next.settings.exclusionList()).singleElement().satisfies(e->{assertThat(e.value()).isEqualTo("other");assertThat(e.wasLearned()).isTrue();});
        assertThat(next.settings.automaticReplanning()).isFalse();assertThat(next.learnedSignals).isNotEmpty();
    }
    @Test void learnedExclusionsNeverBlockAMeal(){
        product(Map.of("sku","plain","price",n("2")));var b=start(published());
        var s=new Settings(n("100"),"AUD",Preference.ECONOMY,Fulfilment.PICKUP,List.of(),List.of(),false,List.of(new Exclusion(ExclusionType.SKU,"plain",true)));
        var changed=procurement.change(MEMBER,b.basketId,new Change(b.version,s));
        assertThat(changed.state).isEqualTo(ProcurementModels.State.DRAFT);assertThat(changed.evaluation.explanation()).contains("learned preferences never block a meal");
    }
}
