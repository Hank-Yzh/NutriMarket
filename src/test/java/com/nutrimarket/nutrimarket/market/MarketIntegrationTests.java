package com.nutrimarket.nutrimarket.market;

import com.nutrimarket.nutrimarket.dao.market.MarketMapper;
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
    "spring.datasource.url=jdbc:h2:mem:market-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.sql.init.schema-locations=classpath:schema-h2.sql",
    "nutrimarket.market.operator-usernames[0]=reviewer",
    "nutrimarket.health.expert-usernames[0]=reviewer","nutrimarket.health.expert-usernames[1]=member",
    "nutrimarket.dietary.delivery-delay-ms=3600000"})
class MarketIntegrationTests {
    @Autowired com.nutrimarket.nutrimarket.service.planning.IngredientCatalog ingredients;@Autowired com.nutrimarket.nutrimarket.service.market.MarketEngine marketEngine;
    @Autowired MarketService market;@Autowired MarketMapper markets;@Autowired CatalogService catalog;
    @MockitoBean MarketSemanticClient matcher;
    static final String MEMBER="00000000-0000-0000-0000-000000000301",OTHER="00000000-0000-0000-0000-000000000302",EXPERT="00000000-0000-0000-0000-000000000303";
    static final Instant NOW=Instant.parse("2026-10-13T00:00:00Z");
    static final LocalDate WEEK=LocalDate.parse("2026-10-19");
    @Autowired PlanningService planning;@Autowired PlanningMapper mapper;@Autowired PlanningEngine engine;
    @Autowired HealthService health;@Autowired HealthMapper healthMapper;@Autowired UserMapper users;
    @Autowired JwtTokenService tokens;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    @MockitoBean Clock clock;@MockitoBean NutritionClient nutrition;@MockitoBean PlanningClient model;
    @Value("${local.server.port}") int port;
    String token,otherToken,expertToken;
    @BeforeEach void setup(){
        when(clock.instant()).thenReturn(NOW);
        for(String table:List.of("procurement_order","shopping_basket","market_outbox","market_result","market_product_history","market_product","planning_outbox","nutrition_plan","weekly_health_assessment","health_intake","member_health_profile","dietary_outbox","dietary_record","member_account"))jdbc.update("DELETE FROM "+table);
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
    Assessment assessment(List<String> allergies){
        var profile=health.saveProfile(MEMBER,input(0,allergies));
        var a=new Assessment();a.assessmentId=UUID.randomUUID().toString();a.memberId=MEMBER;a.profileVersion=profile.version();a.periodStart=WEEK.minusWeeks(2);a.periodEnd=a.periodStart.plusDays(6);a.timezone="Australia/Sydney";a.policyVersion="health-demo-v1";a.status=AssessmentStatus.COMPLETED;a.planningClearance=Clearance.CLEARED;a.riskStatus=Risk.WITHIN_TARGETS;a.createdAt=NOW;a.updatedAt=NOW;a.healthConstraintSet=new Constraints(allergies,List.of(),List.of(),targets(),List.of());healthMapper.insertAssessment(a,json.writeValueAsString(a));return a;
    }
    static CandidateMeal meal(int d,Slot s,String ingredient){return new CandidateMeal(d,s,List.of(new Portion(ingredient,n("200"))));}
    static Candidate candidate(String ingredient){List<CandidateMeal> meals=new ArrayList<>();for(int day=0;day<7;day++)for(var slot:Slot.values())meals.add(meal(day,slot,ingredient));return new Candidate(meals);}
    Plan generate(Assessment a){return planning.generate(MEMBER,new Generate(UUID.fromString(a.assessmentId),WEEK,true));}
    Plan publish(Plan p){return planning.publish(MEMBER,p.planId,new Publish(p.version,true));}
    Replan replan(int version,String line,String ingredient){return new Replan(UUID.randomUUID(),version,ReplanReason.AVAILABILITY,"Supplier evidence: ingredient unavailable for this meal",List.of(line),List.of(ingredient),true);}
    HttpResponse<String> request(String method,String path,Object body,String bearer)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/market"+path));if(bearer!=null)b.header("Authorization","Bearer "+bearer);
        b.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    ProductInput facts(){return new ProductInput(0,"verified-store-catalogue","RICE-01","Cooked brown rice pouch","Fixture brand","Cooked grains","rice","READY_TO_EAT",null,"Store A","AUD",n("3"),n("500"),"",Availability.IN_STOCK,NOW,"https://example.invalid/rice",List.of("brown rice"),List.of(),List.of(),List.of("vegan"),true,true,nutrients(),true,true);}
    ProductInput change(ProductInput base,Map<String,Object> changes){var map=json.convertValue(base,new tools.jackson.core.type.TypeReference<Map<String,Object>>(){});map.putAll(changes);return json.convertValue(map,ProductInput.class);}
    Product product(Map<String,Object> changes){return catalog.save(EXPERT,null,change(facts(),changes));}
    Plan published(){return publish(generate(assessment(List.of())));}
    MarketResult match(Plan p){return market.start(MEMBER,new Start(UUID.fromString(p.planId),p.version));}
    Refresh refresh(MarketResult r,List<String> lines){return new Refresh(UUID.randomUUID(),r.revision,lines);}
    List<String> allLines(MarketResult r){return r.planning.weeklyMealPlan().stream().map(Meal::menuLineId).toList();}
    @Test void matchesEveryRequirementWithSourceEvidenceAndForwardsUnchangedPlan(){
        product(Map.of());product(Map.of("store","Store B","sku","RICE-02","price",n("2.50")));var p=published();var r=match(p);
        assertThat(r.state).isEqualTo(State.COMPLETED);assertThat(r.readyForProcurement).isTrue();assertThat(r.requirements).hasSize(21);assertThat(r.multiStoreComparable).isTrue();
        assertThat(r.requirements).allSatisfy(q->{assertThat(q.candidates()).hasSize(2);assertThat(q.candidates()).allSatisfy(m->{assertThat(m.eligible()).isTrue();assertThat(m.hardConstraintStatus()).isEqualTo(Compliance.COMPLIANT);assertThat(m.packagesToCover()).isEqualByComparingTo("1");});});
        var pack=market.packageFor(MEMBER,r.resultId);var original=planning.packageFor(MEMBER,p.planId);assertThat(pack.weeklyMealPlan()).isEqualTo(original.weeklyMealPlan());assertThat(pack.substitutionGroups()).isEqualTo(original.substitutionGroups());assertThat(pack.originalPlanningPackage()).isEqualTo(original);
        assertThat(r.deliveryState).isEqualTo("QUEUED");assertThat(mapper.delivery(p.planId,1).state()).isEqualTo("DELIVERED");assertThat(match(p).resultId).isEqualTo(r.resultId);verify(matcher,times(1)).match(any(),anyList());assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_outbox",Integer.class)).isEqualTo(1);
    }
    @Test void emptyCatalogueExplicitlyBlocksReadinessWithoutInventingProducts(){var r=match(published());assertThat(r.state).isEqualTo(State.NEEDS_ATTENTION);assertThat(r.requirements).allSatisfy(q->{assertThat(q.outcome()).isEqualTo(Outcome.UNMATCHED);assertThat(q.candidates()).isEmpty();});assertThat(market.packageFor(MEMBER,r.resultId).readyForProcurement()).isFalse();verifyNoInteractions(matcher);}
    @Test void apiRequiresJwtOwnershipAndOperatorRole()throws Exception{
        var p=published();var r=match(p);assertThat(request("GET","/results",null,null).statusCode()).isEqualTo(401);assertThat(request("GET","/results/"+r.resultId,null,otherToken).statusCode()).isEqualTo(404);
        assertThat(request("POST","/catalogue",facts(),token).statusCode()).isEqualTo(403);assertThat(request("POST","/catalogue",facts(),expertToken).statusCode()).isEqualTo(200);assertThat(request("POST","/results",new Start(UUID.fromString(p.planId),p.version),otherToken).statusCode()).isEqualTo(404);
        assertThat(request("POST","/results/"+r.resultId+"/refresh",new Refresh(UUID.randomUUID(),1,List.of()),token).statusCode()).isEqualTo(400);assertThat(request("GET","/results?page=0",null,token).statusCode()).isEqualTo(400);
    }
    @Test void catalogueEditsAreVersionedAuditedAndInvalidateEvidence(){var product=product(Map.of());var r=match(published());var changed=catalog.save(EXPERT,product.productId(),change(product.facts(),Map.of("expectedVersion",1,"price",n("4"))));assertThat(changed.version()).isEqualTo(2);
        assertThatThrownBy(()->catalog.save(EXPERT,product.productId(),change(product.facts(),Map.of("expectedVersion",1)))).isInstanceOf(AgentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_product_history",Integer.class)).isEqualTo(2);var stale=market.get(MEMBER,r.resultId);assertThat(stale.state).isEqualTo(State.STALE);assertThat(stale.requirements).allSatisfy(q->assertThat(q.candidates()).allSatisfy(m->assertThat(m.eligible()).isFalse()));assertThatThrownBy(()->market.packageFor(MEMBER,r.resultId)).isInstanceOf(AgentException.class);
        var refreshed=market.refresh(MEMBER,r.resultId,refresh(stale,allLines(stale)));assertThat(refreshed.state).isEqualTo(State.COMPLETED);assertThat(refreshed.requirements.get(0).candidates().get(0).product().version()).isEqualTo(2);
    }
    @Test void missingAllergenEvidenceIsUnknownEvenWithHighModelConfidence(){product(Map.of("allergensComplete",false));var r=match(published());var m=r.requirements.get(0).candidates().get(0);assertThat(m.confidence()).isEqualByComparingTo("1");assertThat(m.hardConstraintStatus()).isEqualTo(Compliance.UNKNOWN);assertThat(m.eligible()).isFalse();assertThat(r.readyForProcurement).isFalse();}
    @Test void declaredCrossContactCannotBeOverriddenByModel(){product(Map.of("mayContain",List.of("milk")));var r=match(publish(generate(assessment(List.of("milk")))));var m=r.requirements.get(0).candidates().get(0);assertThat(m.hardConstraintStatus()).isEqualTo(Compliance.NON_COMPLIANT);assertThat(m.eligible()).isFalse();assertThat(m.reasons()).anyMatch(x->x.contains("cross-contact"));}
    @Test void plannerNutritionTolerancesArePreserved(){var close=new TreeMap<>(nutrients());close.put("1008",new Nutrient(n("101"),"kcal"));var far=new TreeMap<>(nutrients());far.put("1008",new Nutrient(n("120"),"kcal"));
        product(Map.of("sku","close","per100g",close));product(Map.of("sku","far","per100g",far));var candidates=match(published()).requirements.get(0).candidates();
        assertThat(candidates).filteredOn(m->m.product().facts().sku().equals("close")).singleElement().satisfies(m->{assertThat(m.hardConstraintStatus()).isEqualTo(Compliance.COMPLIANT);assertThat(m.eligible()).isTrue();});
        assertThat(candidates).filteredOn(m->m.product().facts().sku().equals("far")).singleElement().satisfies(m->{assertThat(m.hardConstraintStatus()).isEqualTo(Compliance.NON_COMPLIANT);assertThat(m.reasons()).anyMatch(x->x.contains("tolerance"));});}
    @Test void kilojouleLabelsAreConvertedToPlannerUnits(){var kj=new TreeMap<>(nutrients());kj.put("1008",new Nutrient(n("418.4"),"kJ"));product(Map.of("per100g",kj));var m=match(published()).requirements.get(0).candidates().get(0);assertThat(m.hardConstraintStatus()).isEqualTo(Compliance.COMPLIANT);assertThat(m.eligible()).isTrue();}
    @Test void rawProductsUseVerifiedCookedYield(){
        var raw=new TreeMap<String,Nutrient>();raw.put("1008",new Nutrient(n("250"),"kcal"));raw.put("1003",new Nutrient(n("25"),"g"));raw.put("1004",new Nutrient(n("7.5"),"g"));raw.put("1005",new Nutrient(n("37.5"),"g"));
        product(Map.of("preparation","RAW","cookedYieldFactor",n("2.5"),"per100g",raw));var m=match(published()).requirements.get(0).candidates().get(0);
        assertThat(m.hardConstraintStatus()).isEqualTo(Compliance.COMPLIANT);assertThat(m.eligible()).isTrue();assertThat(m.packagesToCover()).isEqualByComparingTo("1");
        assertThatThrownBy(()->product(Map.of("sku","ready","cookedYieldFactor",n("2.5")))).isInstanceOf(AgentException.class);
    }
    @Test void rawShoppingNamesReachMatcherAndWeeklyPriceUsesSoldWeight(){
        var raw=new TreeMap<String,Nutrient>();nutrients().forEach((k,v)->raw.put(k,new Nutrient(v.amount().multiply(n("2.5")),v.unit())));
        product(Map.of("name","Dry brown rice","preparation","RAW","cookedYieldFactor",n("2.5"),"per100g",raw));
        var r=match(published());
        verify(matcher).match(argThat(q->q.description().equals("Brown rice")&&q.quantity().compareTo(n("200"))==0),anyList());
        var ingredient=r.priceComparison.ingredients().get(0);
        assertThat(ingredient.name()).isEqualTo("Brown rice");
        assertThat(ingredient.requiredEdibleGrams()).isEqualByComparingTo("4200");
        assertThat(ingredient.options()).singleElement().satisfies(o->{
            assertThat(o.eligible()).isTrue();assertThat(o.requiredSoldGrams()).isEqualByComparingTo("1680");
            assertThat(o.packages()).isEqualByComparingTo("4");assertThat(o.totalCost()).isEqualByComparingTo("12");
        });
    }
    @Test void refreshingLegacyCookedDescriptionsUsesRawSearchNames(){
        product(Map.of());var r=match(published());
        var legacy=json.readTree(markets.find(r.resultId).document());
        for(var q:legacy.path("planning").path("ingredientRequirements"))((ObjectNode)q).put("description","Cooked brown rice");
        jdbc.update("UPDATE market_result SET document=? WHERE result_id=?",json.writeValueAsString(legacy),r.resultId);
        clearInvocations(matcher);
        var refreshed=market.refresh(MEMBER,r.resultId,refresh(r,List.of("d0-BREAKFAST")));
        verify(matcher).match(argThat(q->q.description().equals("Brown rice")),anyList());
        assertThat(refreshed.planning.ingredientRequirements().get(0).description()).isEqualTo("Cooked brown rice");
        assertThat(refreshed.requirements.get(0).candidates()).allSatisfy(m->assertThat(m.eligible()).isTrue());
        assertThat(refreshed.priceComparison.ingredients().get(0).name()).isEqualTo("Brown rice");
    }
    @Test void unavailableRawIncompleteAndLowConfidenceAreExplicit(){product(Map.of("availability",Availability.OUT_OF_STOCK));product(Map.of("sku","raw","preparation","RAW"));product(Map.of("sku","missing","per100g",Map.of()));var r=match(published());assertThat(r.requirements.get(0).candidates().stream().map(ProductMatch::outcome)).contains(Outcome.UNAVAILABLE,Outcome.INCOMPLETE);assertThat(r.readyForProcurement).isFalse();}
    @Test void lowConfidenceCannotBecomeEligible(){product(Map.of());when(matcher.match(any(),anyList())).thenReturn(Map.of());var r=match(published());assertThat(r.requirements.get(0).outcome()).isEqualTo(Outcome.LOW_CONFIDENCE);assertThat(r.readyForProcurement).isFalse();}
    @Test void modelFailureDoesNotInventSuccessfulMatches(){product(Map.of());when(matcher.match(any(),anyList())).thenThrow(AgentException.provider("MARKET_MODEL_UNAVAILABLE","Unavailable"));var r=match(published());assertThat(r.requirements).allSatisfy(q->assertThat(q.outcome()).isEqualTo(Outcome.MODEL_FAILURE));assertThat(r.readyForProcurement).isFalse();verify(matcher,times(1)).match(any(),anyList());}
    @Test void sourceEvidenceExpiresAndOldPackageCannotBeUsed(){product(Map.of());var r=match(published());when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(25)));assertThat(market.get(MEMBER,r.resultId).state).isEqualTo(State.STALE);assertThat(markets.delivery(r.resultId,1).state()).isEqualTo("CANCELLED");assertThatThrownBy(()->market.packageFor(MEMBER,r.resultId)).isInstanceOf(AgentException.class);}
    @Test void profileChangeInvalidatesMarketAndBlocksRefresh(){product(Map.of());var r=match(published());health.saveProfile(MEMBER,input(1,List.of("milk")));assertThat(market.get(MEMBER,r.resultId).planStale).isTrue();assertThatThrownBy(()->market.refresh(MEMBER,r.resultId,refresh(r,allLines(r)))).isInstanceOf(AgentException.class);}
    @Test void partialRefreshPreservesUnaffectedEvidenceAndDeduplicatesRequests(){product(Map.of());var r=match(published());var input=refresh(r,List.of("d0-BREAKFAST"));var updated=market.refresh(MEMBER,r.resultId,input);assertThat(updated.revision).isEqualTo(2);assertThat(updated.requirements.subList(1,21)).isEqualTo(r.requirements.subList(1,21));assertThat(updated.planning).isEqualTo(r.planning);assertThat(markets.delivery(r.resultId,1).state()).isEqualTo("CANCELLED");assertThat(market.refresh(MEMBER,r.resultId,input).revision).isEqualTo(2);
        assertThatThrownBy(()->market.refresh(MEMBER,r.resultId,new Refresh(input.requestId(),1,List.of("d1-BREAKFAST")))).isInstanceOf(AgentException.class);
    }
    @Test void refreshRequestsCarryPlanBasketAndRequiredFreshness(){product(Map.of("observedAt",NOW.minus(Duration.ofHours(2))));var r=match(published());
        assertThatThrownBy(()->market.refresh(MEMBER,r.resultId,new Refresh(UUID.randomUUID(),r.revision,allLines(r),UUID.randomUUID(),null,null))).isInstanceOf(AgentException.class).hasMessageContaining("different plan");
        assertThatThrownBy(()->market.refresh(MEMBER,r.resultId,new Refresh(UUID.randomUUID(),r.revision,allLines(r),null,null,NOW.plusSeconds(60)))).isInstanceOf(AgentException.class).hasMessageContaining("future");
        var refreshed=market.refresh(MEMBER,r.resultId,new Refresh(UUID.randomUUID(),r.revision,allLines(r),UUID.fromString(r.planId),"basket-context",NOW.minus(Duration.ofHours(1))));
        assertThat(refreshed.requirements).allSatisfy(q->assertThat(q.candidates()).allSatisfy(m->{assertThat(m.eligible()).isFalse();assertThat(m.outcome()).isEqualTo(Outcome.STALE);}));
        assertThat(refreshed.readyForProcurement).isFalse();assertThat(refreshed.refreshes).last().satisfies(a->assertThat(a.basketId()).isEqualTo("basket-context"));}
    @Test void syntheticTestCatalogueIsValidCoversEveryIngredientAndRestampsOnlyWhenOld(){
        var loader=new TestCatalogueLoader(catalog,markets,json,clock);int loaded=loader.load();var products=catalog.list(1,200).items();
        assertThat(loaded).isEqualTo(101);assertThat(products).hasSize(101);
        // Every real product's ingredient list confirms its food; every decoy (apple lollies, tomato sauce...) is rejected.
        assertThat(products).allSatisfy(p->{var problem=ingredients.compositionProblem(p.facts().ingredientId(),p.facts().ingredients());
            if(p.facts().sourceReference().contains("Decoy"))assertThat(problem).as(p.facts().sku()).isNotNull();else assertThat(problem).as(p.facts().sku()).isNull();});
        var stores=new HashMap<String,Set<String>>();products.forEach(p->stores.computeIfAbsent(p.facts().ingredientId(),k->new HashSet<>()).add(p.facts().store()));
        assertThat(stores.keySet()).isEqualTo(ingredients.all().stream().map(com.nutrimarket.nutrimarket.model.planning.PlanningModels.Ingredient::id).collect(java.util.stream.Collectors.toSet()));
        assertThat(stores).allSatisfy((ingredient,set)->assertThat(set).as(ingredient).hasSizeGreaterThanOrEqualTo(2));
        assertThat(products).allSatisfy(p->{assertThat(p.facts().sourceReference()).startsWith("Synthetic test fixture");assertThat(p.facts().observedAt()).isEqualTo(NOW);});
        assertThat(loader.load()).isZero();
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(13)));assertThat(loader.load()).isEqualTo(101);
        assertThat(catalog.list(1,200).items()).allSatisfy(p->{assertThat(p.version()).isEqualTo(2);assertThat(p.facts().observedAt()).isEqualTo(NOW.plus(Duration.ofHours(13)));});
    }
    @Test void weeklyComparisonPricesWholePackagesForTheWeeksTotal(){
        product(Map.of("sku","A-1KG","packageGrams",n("1000"),"price",n("10")));
        product(Map.of("sku","B-500","store","Store B","packageGrams",n("500"),"price",n("4.50")));
        product(Map.of("sku","C-OOS","store","Store C","availability",Availability.OUT_OF_STOCK));
        var c=match(published()).priceComparison;
        assertThat(c.ingredients()).singleElement().satisfies(i->{
            assertThat(i.requiredEdibleGrams()).isEqualByComparingTo("4200");assertThat(i.menuLines()).isEqualTo(21);
            assertThat(i.options().get(0).sku()).isEqualTo("B-500");assertThat(i.options().get(0).totalCost()).isEqualByComparingTo("40.50");
            var a=i.options().stream().filter(o->o.sku().equals("A-1KG")).findFirst().orElseThrow();
            assertThat(a.packages()).isEqualByComparingTo("5");assertThat(a.totalCost()).isEqualByComparingTo("50");assertThat(a.surplusGrams()).isEqualByComparingTo("800");
            assertThat(a.unitPricePerKg()).isEqualByComparingTo("10");assertThat(a.hardConstraintStatus()).isEqualTo(Compliance.COMPLIANT);
            assertThat(a.availability()).isEqualTo(Availability.IN_STOCK);assertThat(a.observedAt()).isEqualTo(NOW);assertThat(a.sourceReference()).isNotBlank();
            assertThat(i.options()).filteredOn(o->o.sku().equals("C-OOS")).singleElement().satisfies(o->{assertThat(o.eligible()).isFalse();assertThat(o.outcome()).isEqualTo(Outcome.UNAVAILABLE);});
        });
        assertThat(c.stores()).extracting(StoreTotal::store).containsExactly("Store B","Store A");
        assertThat(c.stores().get(0).weeklyTotal()).isEqualByComparingTo("40.50");assertThat(c.stores().get(0).missingIngredients()).isEmpty();
    }
    Assessment withConditions(List<String> conditions){var a=assessment(List.of());a.healthConstraintSet=new Constraints(List.of(),List.of(),conditions,targets(),List.of());healthMapper.updateAssessment(a,json.writeValueAsString(a));return a;}
    @Test void foodNameRestrictionsCheckIngredientListsInsteadOfBlockingEverything(){product(Map.of());var a=assessment(List.of());
        a.healthConstraintSet=new Constraints(List.of(),List.of("apple"),List.of(),targets(),List.of());healthMapper.updateAssessment(a,json.writeValueAsString(a));
        var r=match(publish(generate(a)));assertThat(r.readyForProcurement).isTrue();
        assertThat(r.requirements.get(0).candidates()).allSatisfy(m->assertThat(m.reasons()).noneMatch(x->x.contains("apple")));}
    @Test void productsNamedAfterAFoodMustBeThatFoodByIngredientList(){
        product(Map.of());product(Map.of("sku","RICE-PUDDING","name","Creamy Rice Pudding","ingredients",List.of("Skim milk","Sugar","Rice (8%)"),"allergens",List.of("milk")));
        var r=match(published());var pudding=r.requirements.get(0).candidates().stream().filter(m->m.product().facts().sku().equals("RICE-PUDDING")).findFirst().orElseThrow();
        assertThat(pudding.eligible()).isFalse();assertThat(pudding.reasons()).anyMatch(x->x.contains("does not contain brown rice"));
        assertThat(r.requirements.get(0).candidates()).anyMatch(m->m.eligible());}
    ProductMatch candidate(RequirementResult q,String sku){return q.candidates().stream().filter(m->m.product().facts().sku().equals(sku)).findFirst().orElseThrow();}
    @Test void matchedOriginalStillCarriesEligiblePlannerSubstitutesForAgent5(){
        product(Map.of());product(Map.of("sku","WHITE-RICE","name","Long Grain White Rice","ingredientId","white_rice","ingredients",List.of("Long grain white rice (100%)")));
        var r=match(published());var q=r.requirements.get(0);
        assertThat(q.outcome()).isEqualTo(Outcome.MATCHED);assertThat(q.checkedSubstitutes()).containsExactly("white_rice","quinoa","rice_noodles");
        assertThat(candidate(q,"RICE-01").eligible()).isTrue();assertThat(candidate(q,"WHITE-RICE").eligible()).isTrue();
        assertThat(q.messages()).anyMatch(x->x.startsWith("Planner-approved substitutes were also checked"));
        // Coverage still describes the planned food; the substitute is listed as its own option, marked as a substitute.
        var i=r.priceComparison.ingredients().get(0);assertThat(r.priceComparison.stores()).allSatisfy(st->assertThat(st.ingredientsCovered()).isEqualTo(1));
        assertThat(i.options()).anyMatch(o->o.ingredientId().equals("white_rice")&&o.eligible()&&o.reasons().get(0).startsWith("Substitute from the meal plan"));}
    @Test void unavailableOriginalIsCoveredByAnEligiblePlannerSubstitute(){
        product(Map.of("availability",Availability.OUT_OF_STOCK));product(Map.of("sku","WHITE-RICE","name","Long Grain White Rice","ingredientId","white_rice","ingredients",List.of("Long grain white rice (100%)")));
        var q=match(published()).requirements.get(0);
        assertThat(q.outcome()).isEqualTo(Outcome.MATCHED);assertThat(candidate(q,"RICE-01").outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(candidate(q,"WHITE-RICE").eligible()).isTrue();assertThat(q.messages()).anyMatch(x->x.contains("substitutes were searched"));}
    @Test void productOutsideThePlannerGroupIsNeverASubstitute(){
        product(Map.of());product(Map.of("sku","POTATO","name","Washed Potatoes","ingredientId","potato","ingredients",List.of("Potatoes")));
        var p=published();var q=match(p).requirements.get(0);
        assertThat(q.candidates()).noneMatch(m->m.product().facts().ingredientId().equals("potato"));
        var pkg=planning.packageFor(MEMBER,p.planId);var req=pkg.ingredientRequirements().get(0);
        var group=pkg.substitutionGroups().stream().filter(g->g.substitutionGroupId().equals(req.substitutionGroupId())).findFirst().orElseThrow();
        var potato=catalog.list(1,50).items().stream().filter(x->x.facts().sku().equals("POTATO")).findFirst().orElseThrow();
        var classified=marketEngine.classify(pkg,req,group,potato,BigDecimal.ONE,"TEST");
        assertThat(classified.eligible()).isFalse();assertThat(classified.reasons()).anyMatch(x->x.contains("outside the planner's permitted substitution group"));}
    @Test void substituteWithIncompleteAllergenEvidenceIsUnknownNotCompliant(){
        product(Map.of("availability",Availability.OUT_OF_STOCK));
        var input=new HashMap<String,Object>(Map.of("sku","WHITE-RICE","name","Long Grain White Rice","ingredientId","white_rice","ingredients",List.of("Long grain white rice (100%)")));input.put("allergensComplete",false);product(input);
        var q=match(published()).requirements.get(0);var sub=candidate(q,"WHITE-RICE");
        assertThat(sub.hardConstraintStatus()).isEqualTo(Compliance.UNKNOWN);assertThat(sub.eligible()).isFalse();
        assertThat(sub.reasons()).anyMatch(x->x.contains("allergen"));assertThat(q.outcome()).isEqualTo(Outcome.UNAVAILABLE);}
    @Test void noPermittedSubstitutionsMeansNoSubstituteCandidates(){
        product(Map.of());product(Map.of("sku","WHITE-RICE","name","Long Grain White Rice","ingredientId","white_rice","ingredients",List.of("Long grain white rice (100%)")));var a=assessment(List.of());
        // Restrictions exclude every alternative the planner could offer for brown rice.
        a.healthConstraintSet=new Constraints(List.of(),List.of("white rice","quinoa","rice noodles"),List.of(),targets(),List.of());healthMapper.updateAssessment(a,json.writeValueAsString(a));
        var p=publish(generate(a));assertThat(planning.packageFor(MEMBER,p.planId).substitutionGroups()).allSatisfy(g->assertThat(g.substituteList()).isEmpty());
        var q=match(p).requirements.get(0);
        assertThat(q.checkedSubstitutes()).isEmpty();assertThat(q.candidates()).allSatisfy(m->assertThat(m.product().facts().ingredientId()).isEqualTo("rice"));
        assertThat(q.messages()).noneMatch(x->x.toLowerCase().contains("substitute"));}
    @Test void routineConditionsDoNotBlockProducts(){product(Map.of());var r=match(publish(generate(withConditions(List.of("High cholesterol")))));assertThat(r.readyForProcurement).isTrue();}
    @Test void unusualConditionsKeepProductsUnknownEvenAfterPublishing(){product(Map.of());var p=generate(withConditions(List.of("Chronic kidney disease")));assertThat(p.state).isEqualTo(com.nutrimarket.nutrimarket.model.planning.PlanningModels.State.DRAFT);
        var r=match(publish(p));
        assertThat(r.readyForProcurement).isFalse();assertThat(r.requirements.get(0).candidates()).allSatisfy(m->{assertThat(m.hardConstraintStatus()).isEqualTo(Compliance.UNKNOWN);assertThat(m.reasons()).anyMatch(x->x.contains("Chronic kidney disease"));});}
    @Test void cumulativeRefreshHistoryDoesNotLockAnOtherwiseValidPlan(){
        product(Map.of());var r=match(published());String id=r.resultId;
        assertThatThrownBy(()->market.refresh(MEMBER,id,new Refresh(UUID.randomUUID(),99,List.of("d0-BREAKFAST")))).isInstanceOf(AgentException.class);
        for(int i=0;i<5;i++)r=market.refresh(MEMBER,id,refresh(r,allLines(r)));
        assertThat(r.refreshCount).isEqualTo(5);assertThat(r.state).isEqualTo(State.COMPLETED);
    }
    @Test void expiredRejectedAlternativeDoesNotInvalidateFreshEligibleProducts(){
        product(Map.of());product(Map.of("sku","OLD-RICE","observedAt",NOW.minus(Duration.ofHours(25))));
        var r=match(published());assertThat(r.state).isEqualTo(State.COMPLETED);
        assertThat(r.requirements).allSatisfy(q->{assertThat(q.candidates()).anyMatch(ProductMatch::eligible);assertThat(q.candidates()).anyMatch(m->m.outcome()==Outcome.STALE);});
        assertThat(market.packageFor(MEMBER,r.resultId).readyForProcurement()).isTrue();
    }
    @Test void unchangedExpiredEvidenceWaitsWithoutSpendingRetriesAndRecoversOnUpdate(){
        var product=product(Map.of("observedAt",NOW.minus(Duration.ofHours(25))));var r=match(published());
        assertThat(r.state).isEqualTo(State.STALE);assertThat(r.refreshCount).isZero();assertThat(r.consecutiveRecoveryFailures).isZero();
        clearInvocations(matcher);var waiting=market.refresh(MEMBER,r.resultId,refresh(r,allLines(r)));
        assertThat(waiting.revision).isEqualTo(1);assertThat(waiting.recoveryMessage).contains("Waiting");assertThat(market.recoveryDue(waiting)).isFalse();verifyNoInteractions(matcher);
        assertThatThrownBy(()->market.packageFor(MEMBER,r.resultId)).isInstanceOf(AgentException.class);
        catalog.save(EXPERT,product.productId(),change(product.facts(),Map.of("expectedVersion",1,"observedAt",NOW)));
        assertThat(market.recoveryDue(waiting)).isTrue();var recovered=market.refresh(MEMBER,r.resultId,refresh(waiting,allLines(waiting)));
        assertThat(recovered.state).isEqualTo(State.COMPLETED);assertThat(recovered.consecutiveRecoveryFailures).isZero();assertThat(recovered.recoveryMessage).isNull();
    }
    @Test void unrelatedCatalogueChangeDoesNotWakeWaitingResult(){
        product(Map.of("observedAt",NOW.minus(Duration.ofHours(25))));var r=match(published());
        assertThat(market.recoveryDue(r)).isFalse();
        String unrelated=ingredients.all().stream().map(i->i.id()).filter(id->r.planning.ingredientRequirements().stream().noneMatch(q->q.ingredientId().equals(id))&&r.planning.substitutionGroups().stream().noneMatch(g->g.substituteList().stream().anyMatch(sub->sub.ingredientId().equals(id)))).findFirst().orElseThrow();
        product(Map.of("ingredientId",unrelated,"sku","UNRELATED"));
        assertThat(market.recoveryDue(r)).isFalse();
    }
    @Test void invalidatingOneOfferKeepsOtherFreshOffersVisible(){
        var old=product(Map.of("observedAt",NOW.minus(Duration.ofHours(23))));product(Map.of("sku","FRESH-RICE"));var r=match(published());
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(2)));
        var stale=market.get(MEMBER,r.resultId);assertThat(stale.state).isEqualTo(State.STALE);
        assertThat(stale.requirements).allSatisfy(q->{assertThat(q.candidates()).anyMatch(ProductMatch::eligible);assertThat(q.candidates()).anyMatch(m->m.product().productId().equals(old.productId())&&!m.eligible());});
        var recovered=market.refresh(MEMBER,r.resultId,refresh(stale,allLines(stale)));
        assertThat(recovered.state).isEqualTo(State.COMPLETED);assertThat(recovered.readyForProcurement).isTrue();
    }
    @Test void serviceFailuresUseBackoffAndResumeWithoutReview(){
        product(Map.of());when(matcher.match(any(),anyList())).thenThrow(AgentException.provider("MARKET_MODEL_UNAVAILABLE","Temporary failure"));
        var r=match(published());assertThat(r.consecutiveRecoveryFailures).isEqualTo(1);assertThat(r.nextRecoveryAt).isEqualTo(NOW.plusSeconds(30));
        assertThat(markets.delivery(r.resultId,r.revision)).isNotNull();clearInvocations(matcher);
        var waiting=market.refresh(MEMBER,r.resultId,refresh(r,allLines(r)));assertThat(waiting.revision).isEqualTo(1);verifyNoInteractions(matcher);
        for(int i=0;i<3;i++){
            when(clock.instant()).thenReturn(r.nextRecoveryAt);r=market.refresh(MEMBER,r.resultId,refresh(r,allLines(r)));
        }
        assertThat(r.consecutiveRecoveryFailures).isEqualTo(4);assertThat(Duration.between(clock.instant(),r.nextRecoveryAt)).isEqualTo(Duration.ofMinutes(30));
        when(clock.instant()).thenReturn(r.nextRecoveryAt);
        doAnswer(call->{List<Product> ps=call.getArgument(1);Map<String,BigDecimal> values=new HashMap<>();ps.forEach(p->values.put(p.productId(),BigDecimal.ONE));return values;}).when(matcher).match(any(),anyList());
        var recovered=market.refresh(MEMBER,r.resultId,refresh(r,allLines(r)));assertThat(recovered.state).isEqualTo(State.COMPLETED);assertThat(recovered.nextRecoveryAt).isNull();assertThat(recovered.consecutiveRecoveryFailures).isZero();
    }
    @Test void planChangedDuringMatchingDiscardsLateProviderResponse(){product(Map.of());var p=published();when(matcher.match(any(),anyList())).thenAnswer(call->{health.saveProfile(MEMBER,input(1,List.of()));return Map.of();});var r=match(p);assertThat(r.state).isEqualTo(State.STALE);assertThat(r.requirements).isEmpty();assertThat(markets.delivery(r.resultId,1)).isNull();}
    @Test void invalidPublishedContractIsRejectedBeforeMatching(){var p=published();jdbc.update("UPDATE planning_outbox SET payload=? WHERE plan_id=?","{\"schemaVersion\":1}",p.planId);assertThatThrownBy(()->match(p)).isInstanceOf(AgentException.class);verifyNoInteractions(matcher);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_result",Integer.class)).isZero();}
    @Test void differentCurrenciesDisableCrossStoreOptimisation(){product(Map.of());product(Map.of("store","Store B","currency","USD"));var r=match(published());assertThat(r.readyForProcurement).isTrue();assertThat(r.multiStoreComparable).isFalse();}
    @Test void internalPlanningDeliveryHandsOffPublishedPackage(){product(Map.of());var p=published();var policy=new PlanningPolicy("planning-v1","qwen-plus",Duration.ofSeconds(1),3,2,"","",Duration.ofSeconds(1),3,0.2);new PlanningDelivery(mapper,planning,policy,market).deliverPending();assertThat(markets.forPlan(p.planId,1)).isNotNull();assertThat(mapper.delivery(p.planId,1).state()).isEqualTo("DELIVERED");}

    com.nutrimarket.nutrimarket.config.MarketPolicy deliveryPolicy(String endpoint){return new com.nutrimarket.nutrimarket.config.MarketPolicy("market-v1",com.nutrimarket.nutrimarket.config.MarketPolicy.Source.LOCAL,"","",Duration.ofSeconds(2),Duration.ofHours(24),3,20,true,"qwen-plus",Duration.ofSeconds(2),.85,List.of("reviewer"),endpoint,"",Duration.ofSeconds(2),3);}
    @Test void procurementDeliveryRetriesAndDeduplicatesVersionedInput()throws Exception{
        product(Map.of());var r=match(published());var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/procurement",exchange->{assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo(r.resultId+":1");var payload=json.readTree(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));assertThat(payload.path("readyForProcurement").asBoolean()).isTrue();exchange.sendResponseHeaders(calls.incrementAndGet()==1?503:204,-1);exchange.close();});server.start();
        try{var delivery=new MarketDelivery(markets,market,deliveryPolicy("http://127.0.0.1:"+server.getAddress().getPort()+"/procurement"));delivery.deliverPending();assertThat(markets.delivery(r.resultId,1).state()).isEqualTo("QUEUED");delivery.deliverPending();delivery.deliverPending();assertThat(calls.get()).isEqualTo(2);assertThat(markets.delivery(r.resultId,1).state()).isEqualTo("DELIVERED");}finally{server.stop(0);}
    }
    @Test void staleProcurementInputIsCancelledBeforeHttp(){product(Map.of());var r=match(published());health.saveProfile(MEMBER,input(1,List.of()));new MarketDelivery(markets,market,deliveryPolicy("http://127.0.0.1:1/unused")).deliverPending();assertThat(markets.delivery(r.resultId,1).state()).isEqualTo("CANCELLED");assertThat(markets.delivery(r.resultId,1).attempts()).isZero();}
    @Test void simultaneousMatchingUsesOnePersistedLease()throws Exception{
        product(Map.of());var p=published();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);when(matcher.match(any(),anyList())).thenAnswer(call->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();List<Product> products=call.getArgument(1);return Map.of(products.get(0).productId(),BigDecimal.ONE);});var executor=Executors.newSingleThreadExecutor();
        try{var pending=executor.submit(()->match(p));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();var duplicate=match(p);assertThat(duplicate.state).isEqualTo(State.MATCHING);release.countDown();assertThat(pending.get(10,TimeUnit.SECONDS).resultId).isEqualTo(duplicate.resultId);verify(matcher,times(1)).match(any(),anyList());}finally{release.countDown();executor.shutdownNow();}
    }
}
