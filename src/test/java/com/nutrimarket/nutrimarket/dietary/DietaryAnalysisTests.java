package com.nutrimarket.nutrimarket.dietary;

import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.integration.dietary.NutritionClient;
import com.nutrimarket.nutrimarket.integration.dietary.VisionClient;
import com.nutrimarket.nutrimarket.service.dietary.DietaryAnalysisService;
import com.nutrimarket.nutrimarket.service.dietary.IntakeDelivery;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static com.nutrimarket.nutrimarket.model.dietary.DietaryModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=", "debug=false",
        "spring.datasource.url=${test.database.url:jdbc:h2:mem:dietary-tests;MODE=MySQL;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${test.database.username:sa}", "spring.datasource.password=${test.database.password:}",
        "spring.sql.init.schema-locations=${test.database.schema:classpath:schema-h2.sql}",
        "nutrimarket.dietary.delivery-delay-ms=3600000"})
class DietaryAnalysisTests {
    @Autowired DietaryAnalysisService service;
    @Autowired com.nutrimarket.nutrimarket.dao.auth.UserMapper users;
    @Autowired com.nutrimarket.nutrimarket.service.auth.JwtTokenService tokens;
    String authorization;
    static final String MEMBER_ID="00000000-0000-0000-0000-000000000100";
    static final String OTHER_ID="00000000-0000-0000-0000-000000000102";
    @Autowired JdbcTemplate jdbc;
    @Autowired com.nutrimarket.nutrimarket.dao.dietary.IntakeOutboxMapper outboxMapper;
    @Autowired com.nutrimarket.nutrimarket.dao.dietary.IntakeRecordMapper recordMapper;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired ObjectMapper mapper;
    @Value("${local.server.port}") int port;
    @MockitoBean VisionClient vision;
    @MockitoBean NutritionClient nutrition;
    @MockitoBean Clock clock;
    static final Instant NOW=Instant.parse("2026-10-10T08:00:00Z");
    byte[] photo;
    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM dietary_outbox"); jdbc.update("DELETE FROM dietary_record");
        when(clock.instant()).thenReturn(NOW);
        jdbc.update("DELETE FROM member_account");
        var user=new com.nutrimarket.nutrimarket.model.auth.AuthModels.UserAccount(
                MEMBER_ID,"dietary_tester","unused-test-hash",true,NOW);
        users.insert(user);
        users.insert(new com.nutrimarket.nutrimarket.model.auth.AuthModels.UserAccount(OTHER_ID,"other_tester","unused-test-hash",true,NOW));
        authorization="Bearer "+tokens.issue(user).accessToken();
        photo=testPhoto();
        when(vision.analyze(any(),anyString())).thenReturn(new Recognition(true,"",List.of(food("0.70", "150"))));
        when(nutrition.lookup(any())).thenReturn(match("0.95"));
    }
    static FoodInput food(String confidence,String grams) {
        return new FoodInput("Rice","Rice, cooked",new BigDecimal(grams),new BigDecimal(confidence),null);
    }
    static NutritionMatch match(String confidence) {
        return new NutritionMatch(123,"Rice, cooked",new BigDecimal(confidence),Map.of(
                "1008",new Nutrient(new BigDecimal("130"),"kcal"),"1003",new Nutrient(new BigDecimal("2.7"),"g"),
                "1004",new Nutrient(new BigDecimal("0.3"),"g"),"1005",new Nutrient(new BigDecimal("28"),"g")),
                new Provenance("USDA FoodData Central","123","https://example.test/123",NOW));
    }
    static byte[] testPhoto() throws Exception {
        var image=new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<256;y++) for(int x=0;x<256;x++) image.setRGB(x,y,((x+y)%256<<16)|(x<<8)|y);
        var bytes=new ByteArrayOutputStream(); ImageIO.write(image,"png",bytes); return bytes.toByteArray();
    }
    IntakeRecord submit(UUID id) { return service.submit(id,MEMBER_ID,1,NOW,true,photo); }
    int outboxCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM dietary_outbox",Integer.class); }

    @Test void pendingRecordsAreNotPublishedAndConfirmationIsIdempotent() {
        var id=UUID.randomUUID(); var r=submit(id);
        assertThat(r.status).isEqualTo(Status.PENDING_MEMBER_CONFIRMATION);
        assertThat(r.nutrients.get("1008").amount()).isEqualByComparingTo("195");
        assertThat(outboxCount()).isZero();
        assertThatThrownBy(()->service.validated(id,MEMBER_ID)).isInstanceOf(AgentException.class);
        service.confirm(id,MEMBER_ID); service.confirm(id,MEMBER_ID);
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(service.validated(id,MEMBER_ID).confirmedAt()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("SELECT photo FROM dietary_record WHERE record_id=?",byte[].class,id.toString())).isNull();
    }
    @Test void confidentRecordIsAutomaticallyPublished() {
        when(vision.analyze(any(),anyString())).thenReturn(new Recognition(true,"",List.of(food("0.99","100"))));
        var r=submit(UUID.randomUUID());
        assertThat(r.status).isEqualTo(Status.VALIDATED); assertThat(outboxCount()).isEqualTo(1);
    }
    @Test void fuzzyNutritionMatchRequiresConfirmationEvenWithConfidentVision() {
        when(vision.analyze(any(),anyString())).thenReturn(new Recognition(true,"",List.of(food("0.99","100"))));
        when(nutrition.lookup(any())).thenReturn(match("0.60"));
        assertThat(submit(UUID.randomUUID()).status).isEqualTo(Status.PENDING_MEMBER_CONFIRMATION);
    }
    @Test void sameRecordIdAndPayloadDoesNotRepeatProviderCalls() {
        var id=UUID.randomUUID(); submit(id); submit(id);
        verify(vision,times(1)).analyze(any(),anyString()); verify(nutrition,times(1)).lookup(any());
        assertThatThrownBy(()->service.submit(id,MEMBER_ID,2,NOW,true,photo))
                .isInstanceOf(AgentException.class).extracting(e->((AgentException)e).code()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }
    @Test void migratedRecordRetainsIdempotencyAfterIdentityChangeAndPhotoRemoval() throws Exception {
        var id=UUID.randomUUID();
        submit(id); service.confirm(id,MEMBER_ID);
        // A migrated record retains the request fingerprint made with the previous username.
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        digest.update(("dietary_tester\n1\n"+NOW+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String legacyFingerprint=HexFormat.of().formatHex(digest.digest(photo));
        jdbc.update("UPDATE dietary_record SET fingerprint=?,fingerprint_member_id=? WHERE record_id=?",
                legacyFingerprint,"dietary_tester",id.toString());
        assertThat(recordMapper.find(id.toString()).photo()).isNull();
        assertThat(submit(id).status).isEqualTo(Status.VALIDATED);
        verify(vision,times(1)).analyze(any(),anyString());
        assertThatThrownBy(()->service.submit(id,MEMBER_ID,2,NOW,true,photo)).isInstanceOf(AgentException.class);
    }
    @Test void textOnlyMealIsRetainedAndRequiresConfirmationBeforePublication() {
        String description="米饭200克，鸡肉150克";
        when(vision.analyze(isNull(),eq("text/plain"),eq(description)))
                .thenReturn(new Recognition(true,"",List.of(food("0.99","200"))));
        var id=UUID.randomUUID();var r=service.submit(id,MEMBER_ID,1,NOW,true,null,description);
        assertThat(r.inputMethod).isEqualTo(InputMethod.TEXT);
        assertThat(r.status).isEqualTo(Status.PENDING_MEMBER_CONFIRMATION);
        assertThat(outboxCount()).isZero();
        assertThat(service.get(id,MEMBER_ID).mealDescription).isEqualTo(description);
        assertThat(recordMapper.find(id.toString()).photo()).isNull();
        assertThat(service.confirm(id,MEMBER_ID).status).isEqualTo(Status.VALIDATED);
        var payload=service.validated(id,MEMBER_ID);
        assertThat(payload.nutrients().get("1008").amount()).isEqualByComparingTo("260");
        assertThat(payload.provenance()).extracting(Provenance::provider).contains("Member meal description","USDA FoodData Central");
        assertThat(service.submit(id,MEMBER_ID,1,NOW,true,null,description).status).isEqualTo(Status.VALIDATED);
        verify(vision,times(1)).analyze(isNull(),eq("text/plain"),eq(description));
        assertThatThrownBy(()->service.submit(id,MEMBER_ID,1,NOW,true,null,"Grilled chicken"))
                .isInstanceOf(AgentException.class).extracting(e->((AgentException)e).code()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }
    @Test void textOnlyRetryUsesRetainedDescriptionWithoutRequiringAPhoto() {
        String description="A bowl of cooked rice, about 150g";
        when(vision.analyze(isNull(),eq("text/plain"),eq(description)))
                .thenThrow(AgentException.provider("VISION_UNAVAILABLE","Try later"))
                .thenReturn(new Recognition(true,"",List.of(food("0.7","150"))));
        var id=UUID.randomUUID();
        assertThat(service.submit(id,MEMBER_ID,1,NOW,true,null,description).status).isEqualTo(Status.DEFERRED);
        assertThat(service.retry(id,MEMBER_ID).status).isEqualTo(Status.PENDING_MEMBER_CONFIRMATION);
        verify(vision,times(2)).analyze(isNull(),eq("text/plain"),eq(description));
    }
    @Test void supplementaryDescriptionIsPassedWithThePhotoAndIncludedInIdempotency() {
        String description="Shared rice plate; I ate about half";
        when(vision.analyze(any(),eq("image/png"),eq(description)))
                .thenReturn(new Recognition(true,"",List.of(food("0.7","150"))));
        var id=UUID.randomUUID();var r=service.submit(id,MEMBER_ID,1,NOW,true,photo,description);
        assertThat(r.inputMethod).isEqualTo(InputMethod.PHOTO_AND_TEXT);
        assertThat(r.mealDescription).isEqualTo(description);
        service.submit(id,MEMBER_ID,1,NOW,true,photo,description);
        verify(vision,times(1)).analyze(eq(photo),eq("image/png"),eq(description));
        assertThatThrownBy(()->service.submit(id,MEMBER_ID,1,NOW,true,photo,"I ate all of it"))
                .isInstanceOf(AgentException.class).extracting(e->((AgentException)e).code()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }
    @Test void emptyOversizedOrUnconsentedDescriptionsDoNotCreateRecords() {
        assertThatThrownBy(()->service.submit(UUID.randomUUID(),MEMBER_ID,1,NOW,true,null,"  "))
                .isInstanceOf(AgentException.class).extracting(e->((AgentException)e).code()).isEqualTo("MEAL_INPUT_REQUIRED");
        assertThatThrownBy(()->service.submit(UUID.randomUUID(),MEMBER_ID,1,NOW,true,null,"a".repeat(3001))).isInstanceOf(AgentException.class);
        assertThatThrownBy(()->service.submit(UUID.randomUUID(),MEMBER_ID,1,NOW,false,null,"Cooked rice")).isInstanceOf(AgentException.class);
        verifyNoInteractions(vision,nutrition);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dietary_record",Integer.class)).isZero();
    }
    @Test void vagueTextIsReportedWithoutInventingNutrition() {
        when(vision.analyze(isNull(),eq("text/plain"),anyString()))
                .thenReturn(new Recognition(false,"Describe the food and approximate portions.",List.of()));
        var r=service.submit(UUID.randomUUID(),MEMBER_ID,1,NOW,true,null,"I had lunch");
        assertThat(r.status).isEqualTo(Status.REPLACEMENT_REQUIRED);
        assertThat(r.failures.get(0).code()).isEqualTo("UNUSABLE_DESCRIPTION");
        verifyNoInteractions(nutrition);assertThat(outboxCount()).isZero();
    }
    HttpResponse<String> uploadDescription(String description) throws Exception {
        String boundary="NutriMarketDescriptionBoundary";var body=new ByteArrayOutputStream();
        var fields=Map.of("recordId",UUID.randomUUID().toString(),"profileVersion","1",
                "mealTime",NOW.toString(),"photoProcessingConsent","true","mealDescription",description);
        for(var field:fields.entrySet()) body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+
                field.getKey()+"\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"+field.getValue()+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(("--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/dietary-records"))
                .header("Authorization",authorization).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
    }
    @Test void multipartTextUploadWorksWithoutAPhotoAndRejectsEmptyMeals() throws Exception {
        String description="米饭200克";
        when(vision.analyze(isNull(),eq("text/plain"),eq(description)))
                .thenReturn(new Recognition(true,"",List.of(food("0.7","200"))));
        var response=uploadDescription(description);
        assertThat(response.statusCode()).isEqualTo(200);
        var value=mapper.readTree(response.body());
        assertThat(value.path("memberId").asString()).isEqualTo(MEMBER_ID);
        assertThat(value.path("mealDescription").asString()).isEqualTo(description);
        assertThat(value.path("inputMethod").asString()).isEqualTo("TEXT");
        assertThat(uploadDescription("").statusCode()).isEqualTo(400);
        assertThat(uploadDescription("a".repeat(3001)).statusCode()).isEqualTo(400);
    }
    @Test void invalidImageDoesNotCallProvidersOrCreateRecord() {
        assertThatThrownBy(()->service.submit(UUID.randomUUID(),MEMBER_ID,1,NOW,true,new byte[]{1,2,3}))
                .isInstanceOf(AgentException.class).extracting(e->((AgentException)e).code()).isEqualTo("INVALID_IMAGE");
        verifyNoInteractions(vision,nutrition);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dietary_record",Integer.class)).isZero();
    }
    @Test void unusableFoodPhotoIsDistinctFromServiceFailure() {
        when(vision.analyze(any(),anyString())).thenReturn(new Recognition(false,"Blurry photo",List.of()));
        var r=submit(UUID.randomUUID()); assertThat(r.status).isEqualTo(Status.REPLACEMENT_REQUIRED);
        verifyNoInteractions(nutrition); assertThat(outboxCount()).isZero();
    }
    @Test void backendFailureRetainsPhotoAndRetryUsesSameRecord() {
        when(vision.analyze(any(),anyString())).thenThrow(AgentException.provider("VISION_UNAVAILABLE","Try again later"))
                .thenReturn(new Recognition(true,"",List.of(food("0.7","150"))));
        var id=UUID.randomUUID(); var r=submit(id);
        assertThat(r.status).isEqualTo(Status.DEFERRED);
        assertThat(r.failures.get(0).code()).isEqualTo("VISION_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT photo FROM dietary_record WHERE record_id=?",byte[].class,id.toString())).isEqualTo(photo);
        assertThat(service.retry(id,MEMBER_ID).status).isEqualTo(Status.PENDING_MEMBER_CONFIRMATION);
    }
    @Test void nutritionFailureRetriesWithoutReanalyzingImage() {
        when(nutrition.lookup(any())).thenThrow(AgentException.provider("NUTRITION_UNAVAILABLE","Try again later")).thenReturn(match("0.95"));
        var id=UUID.randomUUID(); assertThat(submit(id).status).isEqualTo(Status.DEFERRED);
        assertThat(service.retry(id,MEMBER_ID).status).isEqualTo(Status.PENDING_MEMBER_CONFIRMATION);
        verify(vision,times(1)).analyze(any(),anyString());
    }
    @Test void retriesAreBounded() {
        when(vision.analyze(any(),anyString())).thenThrow(AgentException.provider("VISION_UNAVAILABLE","Try again later"));
        var id=UUID.randomUUID(); submit(id); service.retry(id,MEMBER_ID); service.retry(id,MEMBER_ID);
        assertThatThrownBy(()->service.retry(id,MEMBER_ID)).isInstanceOf(AgentException.class);
        verify(vision,times(3)).analyze(any(),anyString()); assertThat(outboxCount()).isZero();
    }
    @Test void correctedPortionsRecalculateAndStillRequireConfirmation() {
        var id=UUID.randomUUID(); submit(id);
        var r=service.correct(id,MEMBER_ID,new Correction(List.of(food("1.0","200"))));
        assertThat(r.nutrients.get("1008").amount()).isEqualByComparingTo("260");
        assertThat(r.status).isEqualTo(Status.PENDING_MEMBER_CONFIRMATION);
        verify(nutrition,times(2)).lookup(any()); assertThat(outboxCount()).isZero();
        service.confirm(id,MEMBER_ID); assertThat(outboxCount()).isEqualTo(1);
    }
    @Test void abandonedAndExpiredRecordsNeverReachDownstream() {
        var id=UUID.randomUUID(); submit(id); service.abandon(id,MEMBER_ID);
        assertThatThrownBy(()->service.confirm(id,MEMBER_ID)).isInstanceOf(AgentException.class);
        var expired=UUID.randomUUID(); submit(expired); when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(2)));
        assertThat(service.get(expired,MEMBER_ID).status).isEqualTo(Status.EXPIRED);
        assertThatThrownBy(()->service.confirm(expired,MEMBER_ID)).isInstanceOf(AgentException.class);
        assertThat(outboxCount()).isZero();
    }
    @Test void memberCannotReadAnotherMembersRecord() {
        var id=UUID.randomUUID(); submit(id);
        assertThatThrownBy(()->service.get(id,"other-member")).isInstanceOf(AgentException.class)
                .extracting(e->((AgentException)e).status().value()).isEqualTo(404);
    }
    @Test void consentIsRequiredBeforePhotoProcessing() {
        assertThatThrownBy(()->service.submit(UUID.randomUUID(),MEMBER_ID,1,NOW,false,photo)).isInstanceOf(AgentException.class);
        verifyNoInteractions(vision,nutrition);
    }
    @Test void httpApiRequiresAuthenticationAndReturnsStructuredValidationErrors() throws Exception {
        var client=HttpClient.newHttpClient(); var id=UUID.randomUUID(); submit(id);
        var uri=URI.create("http://127.0.0.1:"+port+"/api/v1/dietary-records/"+id);
        assertThat(client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        String auth=authorization;
        var response=client.send(HttpRequest.newBuilder(uri).header("Authorization",auth).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("status").asString()).isEqualTo("PENDING_MEMBER_CONFIRMATION");
        var invalid=client.send(HttpRequest.newBuilder(URI.create(uri+"/foods")).header("Authorization",auth)
                .header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString("{\"foods\":[]}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(invalid.body()).path("code").asString()).isEqualTo("INVALID_REQUEST");
    }

    @Test void multipartUploadCreatesRecordOwnedByAuthenticatedMember() throws Exception {
        String boundary="NutriMarketTestBoundary";
        var body=new ByteArrayOutputStream();
        var fields=Map.of("recordId",UUID.randomUUID().toString(),"profileVersion","1",
                "mealTime",NOW.toString(),"photoProcessingConsent","true");
        for(var field:fields.entrySet()) body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+
                field.getKey()+"\"\r\n\r\n"+field.getValue()+"\r\n").getBytes());
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"meal.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes());
        body.write(photo); body.write(("\r\n--"+boundary+"--\r\n").getBytes());
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/dietary-records"))
                .header("Authorization",authorization)
                .header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("memberId").asString()).isEqualTo(MEMBER_ID);
        assertThat(mapper.readTree(response.body()).path("status").asString()).isEqualTo("PENDING_MEMBER_CONFIRMATION");
    }

    @Test void concurrentDuplicateSubmissionsOnlyAnalyzeOnce() throws Exception {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var id=UUID.randomUUID();
            var first=executor.submit(()->submit(id)); var second=executor.submit(()->submit(id));
            assertThat(first.get().recordId).isEqualTo(second.get().recordId);
            verify(vision,times(1)).analyze(any(),anyString());
        } finally { executor.shutdownNow(); }
    }

    @Test void outboxSendsStablePayloadAndIdempotencyKeyOnlyOnceAfterSuccess() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var key=new java.util.concurrent.atomic.AtomicReference<String>();
        var payload=new java.util.concurrent.atomic.AtomicReference<String>();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/intake",exchange->{
            calls.incrementAndGet(); key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            payload.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204,-1); exchange.close();
        }); server.start();
        try {
            var id=UUID.randomUUID(); submit(id); service.confirm(id,MEMBER_ID);
            var integration=new com.nutrimarket.nutrimarket.config.AgentConfiguration.Integrations(null,null,
                    new com.nutrimarket.nutrimarket.config.AgentConfiguration.HealthAgent(
                            "http://127.0.0.1:"+server.getAddress().getPort()+"/intake","",Duration.ofSeconds(2),3));
            var delivery=new IntakeDelivery(outboxMapper,integration); delivery.deliverPending(); delivery.deliverPending();
            assertThat(calls.get()).isEqualTo(1); assertThat(key.get()).isEqualTo(id.toString());
            assertThat(mapper.readTree(payload.get()).path("recordId").asString()).isEqualTo(id.toString());
            assertThat(delivery.status(id).state()).isEqualTo("DELIVERED");
        } finally { server.stop(0); }
    }

    @Test void failedDeliveryIsRetainedAndStopsAtConfiguredLimit() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/intake",exchange->{ calls.incrementAndGet(); exchange.sendResponseHeaders(503,-1); exchange.close(); });
        server.start();
        try {
            var id=UUID.randomUUID(); submit(id); service.confirm(id,MEMBER_ID);
            var integration=new com.nutrimarket.nutrimarket.config.AgentConfiguration.Integrations(null,null,
                    new com.nutrimarket.nutrimarket.config.AgentConfiguration.HealthAgent(
                            "http://127.0.0.1:"+server.getAddress().getPort()+"/intake","",Duration.ofSeconds(2),2));
            var delivery=new IntakeDelivery(outboxMapper,integration);
            delivery.deliverPending(); delivery.deliverPending(); delivery.deliverPending();
            assertThat(calls.get()).isEqualTo(2); assertThat(outboxCount()).isEqualTo(1);
            assertThat(delivery.status(id).state()).isEqualTo("DELIVERY_FAILED");
            assertThat(delivery.status(id).lastError()).isEqualTo("HEALTH_HTTP_503");
        } finally { server.stop(0); }
    }

    @Test void listPaginatesOnlyTheCurrentMembersRecordsAndFiltersStatus() {
        var first=UUID.fromString("00000000-0000-0000-0000-000000000001");
        var second=UUID.fromString("00000000-0000-0000-0000-000000000002");
        submit(first); submit(second); service.confirm(second,MEMBER_ID);
        service.submit(UUID.randomUUID(),OTHER_ID,1,NOW,true,photo);
        var page=service.list(MEMBER_ID,null,1,1);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(RecordSummary::recordId).containsExactly(first);
        assertThat(service.list(MEMBER_ID,null,2,1).items()).extracting(RecordSummary::recordId).containsExactly(second);
        assertThat(service.list(MEMBER_ID,Status.VALIDATED,1,20).items()).extracting(RecordSummary::recordId).containsExactly(second);
        assertThat(service.list(MEMBER_ID,null,3,1).items()).isEmpty();
    }

    @Test void listFiltersExpiredRecordsBeforePaginationAndDoesNotLoadImages() {
        var id=UUID.randomUUID(); submit(id);
        assertThat(recordMapper.findPage(MEMBER_ID,null,NOW,0,20).get(0).photo()).isNull();
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(2)));
        assertThat(service.list(MEMBER_ID,Status.PENDING_MEMBER_CONFIRMATION,1,20).total()).isZero();
        var expired=service.list(MEMBER_ID,Status.EXPIRED,1,20);
        assertThat(expired.total()).isEqualTo(1);
        assertThat(expired.items().get(0).status()).isEqualTo(Status.EXPIRED);
        assertThat(recordMapper.find(id.toString()).status()).isEqualTo("EXPIRED");
    }

    @Test void listHttpValidatesParametersAndSerializesPagination() throws Exception {
        submit(UUID.randomUUID());
        String base="http://127.0.0.1:"+port+"/api/v1/dietary-records";
        String auth=authorization;
        var client=HttpClient.newHttpClient();
        for(String query:List.of("?page=0","?size=101","?status=UNKNOWN")) {
            var response=client.send(HttpRequest.newBuilder(URI.create(base+query)).header("Authorization",auth).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(mapper.readTree(response.body()).path("code").asString()).isEqualTo("INVALID_REQUEST");
        }
        var response=client.send(HttpRequest.newBuilder(URI.create(base+"?page=1&size=10&status=PENDING_MEMBER_CONFIRMATION"))
                .header("Authorization",auth).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("total").asLong()).isEqualTo(1);
        assertThat(mapper.readTree(response.body()).path("items").size()).isEqualTo(1);
    }

    @Test void myBatisChangesAndOutboxParticipateInOneSpringTransaction() {
        var id=UUID.randomUUID(); submit(id);
        assertThatThrownBy(()->transactions.execute(s->{
            service.confirm(id,MEMBER_ID);
            throw new IllegalStateException("simulate transaction failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(outboxCount()).isZero();
        assertThat(service.get(id,MEMBER_ID).status).isEqualTo(Status.PENDING_MEMBER_CONFIRMATION);
        assertThat(recordMapper.find(id.toString()).photo()).isEqualTo(photo);
    }
}
