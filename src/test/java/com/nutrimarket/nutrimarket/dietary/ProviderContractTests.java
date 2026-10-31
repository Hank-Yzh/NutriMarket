package com.nutrimarket.nutrimarket.dietary;

import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.integration.dietary.QwenVisionClient;
import com.nutrimarket.nutrimarket.integration.dietary.UsdaNutritionClient;
import com.nutrimarket.nutrimarket.integration.http.ProviderHttp;

import com.nutrimarket.nutrimarket.config.AgentConfiguration.*;
import com.nutrimarket.nutrimarket.model.dietary.DietaryModels;
import com.nutrimarket.nutrimarket.model.health.HealthModels;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP against local fixtures: no paid APIs or real meal data used in tests. */
class ProviderContractTests {
    HttpServer server;
    String root;
    JsonMapper mapper=JsonMapper.builder().build();
    String visionResponse;
    String searchResponse;
    String detailsResponse;
    int visionStatus=200;
    AtomicReference<String> visionBody=new AtomicReference<>();
    AtomicReference<String> auth=new AtomicReference<>();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        root="http://127.0.0.1:"+server.getAddress().getPort();
        String content="""
                {"usable":true,"reason":"","foods":[{"name":"Rice","searchTerm":"Rice, cooked","portionGrams":150,"confidence":0.7,"fdcId":null}]}
                """;
        visionResponse=mapper.writeValueAsString(java.util.Map.of("choices",java.util.List.of(
                java.util.Map.of("message",java.util.Map.of("content",content)))));
        searchResponse="""
                {"foods":[{"fdcId":123,"description":"Rice, cooked"}]}
                """;
        detailsResponse="""
                {"fdcId":123,"description":"Rice, cooked","foodNutrients":[
                {"nutrient":{"id":1008,"unitName":"kcal"},"amount":130},
                {"nutrient":{"id":1003,"unitName":"g"},"amount":2.7},
                {"nutrient":{"id":1004,"unitName":"g"},"amount":0.3},
                {"nutrient":{"id":1005,"unitName":"g"},"amount":28},
                {"nutrient":{"id":1087,"unitName":"mg"},"amount":10}]}
                """;
        server.createContext("/chat/completions",exchange->{
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            visionBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes=visionResponse.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(visionStatus,bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/foods/search",exchange->{
            byte[] bytes=searchResponse.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/food/123",exchange->{
            byte[] bytes=detailsResponse.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    Integrations config(String key) {
        return new Integrations(new Vision(root,"qwen-vl-plus",key,Duration.ofSeconds(2)),
                new Nutrition(root,key,Duration.ofSeconds(2)),new HealthAgent("","",Duration.ofSeconds(2),3));
    }
    QwenVisionClient vision(String key) {
        return new QwenVisionClient(config(key),new ProviderHttp(mapper),mapper,Validation.buildDefaultValidatorFactory().getValidator());
    }
    UsdaNutritionClient nutrition() {
        return new UsdaNutritionClient(config("test-key"),new ProviderHttp(mapper),Clock.systemUTC());
    }
    @Test void qwenAcceptsTextOnlyMealsWithoutSendingAnImage() {
        var result=vision("test-key").analyze(null,"text/plain","米饭200克，鸡肉150克");
        assertThat(result.usable()).isTrue();
        assertThat(visionBody.get()).contains("米饭200克，鸡肉150克","untrusted data","Do not invent unmentioned ingredients")
                .doesNotContain("image_url","data:image/");
    }
    @Test void qwenReceivesBothPhotoAndSupplementaryPortionDescription() {
        vision("test-key").analyze(new byte[]{1,2,3},"image/png","I ate one third of the shared plate.");
        assertThat(visionBody.get()).contains("I ate one third of the shared plate.","data:image/png;base64,AQID");
    }
    @Test void qwenSendsBase64ImageAndParsesStructuredFoodResult() {
        var result=vision("test-key").analyze(new byte[]{1,2,3},"image/png");
        assertThat(result.usable()).isTrue(); assertThat(result.foods().get(0).portionGrams()).isEqualByComparingTo("150");
        assertThat(auth.get()).isEqualTo("Bearer test-key");
        assertThat(visionBody.get()).contains("data:image/png;base64,AQID", "qwen-vl-plus", "must be in English", "short English reason");
        assertThat(result.foods().get(0).name()).isEqualTo("Rice");
    }
    @Test void largePhotosAreShrunkToJpegBeforeUpload() throws IOException {
        var png=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(4032,3024,BufferedImage.TYPE_INT_RGB),"png",png);
        vision("test-key").analyze(png.toByteArray(),"image/png");
        String encoded=visionBody.get().replaceFirst("(?s).*data:image/jpeg;base64,([A-Za-z0-9+/=]+).*","$1");
        var sent=ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
        assertThat(sent.getWidth()).isEqualTo(1024); assertThat(sent.getHeight()).isEqualTo(768);
    }
    @Test void malformedVisionDataIsAServiceFailureNotABadPhoto() {
        visionResponse="{\"choices\":[]}";
        assertThatThrownBy(()->vision("test-key").analyze(new byte[]{1},"image/png"))
                .isInstanceOf(AgentException.class).extracting(e->((AgentException)e).code()).isEqualTo("VISION_INVALID_RESPONSE");
    }
    @Test void backendStatusAndMissingKeyAreExplicitAndNeverExposeProviderBody() {
        visionStatus=429; visionResponse="sensitive upstream body";
        assertThatThrownBy(()->vision("test-key").analyze(new byte[]{1},"image/png"))
                .isInstanceOf(AgentException.class).hasMessageNotContaining("sensitive")
                .extracting(e->((AgentException)e).code()).isEqualTo("VISION_HTTP_429");
        assertThatThrownBy(()->vision("").analyze(new byte[]{1},"image/png"))
                .isInstanceOf(AgentException.class).extracting(e->((AgentException)e).code()).isEqualTo("VISION_NOT_CONFIGURED");
    }
    @Test void usdaParsesPer100gNutrientsAndPreservesSourceIdAndUnits() {
        var match=nutrition().lookup(DietaryAnalysisTests.food("0.7","150"));
        assertThat(match.fdcId()).isEqualTo(123);
        assertThat(match.per100g().get("1008").amount()).isEqualByComparingTo("130");
        assertThat(match.per100g().get("1087").unit()).isEqualTo("mg");
        assertThat(match.matchConfidence()).isEqualByComparingTo("0.95");
    }
    @Test void usdaDoesNotTurnMissingMacronutrientsIntoZeros() {
        detailsResponse="{\"fdcId\":123,\"description\":\"Rice\",\"foodNutrients\":[]}";
        assertThatThrownBy(()->nutrition().lookup(DietaryAnalysisTests.food("0.7","150")))
                .isInstanceOf(AgentException.class).extracting(e->((AgentException)e).code()).isEqualTo("NUTRITION_INCOMPLETE");
    }
    @Test void noNutritionMatchIsExplicit() {
        searchResponse="{\"foods\":[]}";
        assertThatThrownBy(()->nutrition().lookup(DietaryAnalysisTests.food("0.7","150")))
                .isInstanceOf(AgentException.class).extracting(e->((AgentException)e).code()).isEqualTo("NUTRITION_NOT_FOUND");
    }

    com.nutrimarket.nutrimarket.integration.planning.QwenPlanningClient planning(String key) {
        var policy=new com.nutrimarket.nutrimarket.config.PlanningPolicy("planning-v1","qwen-plus",Duration.ofSeconds(2),3,2,"","",Duration.ofSeconds(2),3,0.2);
        return new com.nutrimarket.nutrimarket.integration.planning.QwenPlanningClient(config(key),policy,new ProviderHttp(mapper),mapper);
    }
    com.nutrimarket.nutrimarket.model.health.HealthModels.PlanningInput planningInput() {
        var constraints=new com.nutrimarket.nutrimarket.model.health.HealthModels.Constraints(java.util.List.of("milk"),java.util.List.of("vegan"),java.util.List.of(),java.util.Map.of(),java.util.List.of());
        return new com.nutrimarket.nutrimarket.model.health.HealthModels.PlanningInput(1,"private-assessment-id","private-member-id",1,LocalDate.of(2026,10,5),LocalDate.of(2026,10,11),com.nutrimarket.nutrimarket.model.health.HealthModels.Clearance.CLEARED,constraints,null,java.util.List.of(),"health-rolling-v3",Instant.now(),true,2,4);
    }
    @Test void planningUsesTextModelEnglishContractAndOmitsAccountIdentity() {
        String content="{\"meals\":[{\"day\":0,\"slot\":\"BREAKFAST\",\"ingredients\":[{\"ingredientId\":\"rice\",\"grams\":200}]}]}";
        visionResponse=mapper.writeValueAsString(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("message",java.util.Map.of("content","```json\n"+content+"\n```")))));
        var result=planning("test-key").propose(planningInput(),"Goal fixture","Preference fixture",java.util.Map.of(),null,java.util.List.of("d0-BREAKFAST"),java.util.List.of());
        assertThat(result.meals()).hasSize(1);assertThat(result.meals().get(0).ingredients().get(0).grams()).isEqualByComparingTo("200");
        assertThat(auth.get()).isEqualTo("Bearer test-key");
        assertThat(visionBody.get()).contains("qwen-plus","Respond exclusively in English","d0-BREAKFAST","vegan","milk","Goal fixture").doesNotContain("private-assessment-id","private-member-id","test-key","qwen-vl-plus");
        var body=mapper.readTree(visionBody.get());var facts=mapper.readTree(body.path("messages").path(1).path("content").asString());
        assertThat(facts.path("incompleteRecords").asBoolean()).isTrue();assertThat(facts.path("observedDays").asInt()).isEqualTo(2);
        assertThat(body.path("messages").path(0).path("content").asString()).contains("Missing meals are unknown");
    }
    @Test void planningSendsOnlyTargetedNutrients() {
        visionResponse=mapper.writeValueAsString(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("message",java.util.Map.of("content","{\"meals\":[]}")))));
        var constraints=new HealthModels.Constraints(java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.Map.of("1008",new HealthModels.Target(new java.math.BigDecimal("500"),new java.math.BigDecimal("700"),"kcal")),java.util.List.of());
        var input=new HealthModels.PlanningInput(1,"a","m",1,LocalDate.of(2026,10,5),LocalDate.of(2026,10,11),HealthModels.Clearance.CLEARED,constraints,null,java.util.List.of(),"health-rolling-v3",Instant.now(),false,7,21);
        var nutrition=new DietaryModels.NutritionMatch(169704,"Rice fixture",java.math.BigDecimal.ONE,java.util.Map.of("1008",new DietaryModels.Nutrient(new java.math.BigDecimal("123"),"kcal"),"1162",new DietaryModels.Nutrient(new java.math.BigDecimal("0.1"),"mg")),new DietaryModels.Provenance("USDA fixture","169704","https://example.invalid/rice",Instant.now()));
        var source=new PlanningModels.IngredientSource(new PlanningModels.Ingredient("rice","Cooked brown rice","Rice, brown, long-grain, cooked",169704L,java.util.Set.of(),true,true),nutrition);
        planning("test-key").propose(input,"Goals","Preferences",java.util.Map.of("rice",source),null,java.util.List.of(),java.util.List.of());
        assertThat(visionBody.get()).contains("Cooked brown rice","123").doesNotContain("1162","example.invalid/rice");
    }
    @Test void malformedPlanningOutputNeverBecomesAValidCandidate() {
        visionResponse="{\"choices\":[]}";
        assertThatThrownBy(()->planning("test-key").propose(planningInput(),"Goals","Preferences",java.util.Map.of(),null,java.util.List.of(),java.util.List.of()))
            .isInstanceOf(AgentException.class).extracting(e->((AgentException)e).code()).isEqualTo("PLANNING_INVALID_RESPONSE");
    }
    @Test void missingPlanningKeyFailsBeforeAnyHttpRequest() {
        assertThatThrownBy(()->planning("").propose(planningInput(),"Goals","Preferences",java.util.Map.of(),null,java.util.List.of(),java.util.List.of()))
            .isInstanceOf(AgentException.class).extracting(e->((AgentException)e).code()).isEqualTo("PLANNING_NOT_CONFIGURED");
        assertThat(visionBody.get()).isNull();
    }
}
