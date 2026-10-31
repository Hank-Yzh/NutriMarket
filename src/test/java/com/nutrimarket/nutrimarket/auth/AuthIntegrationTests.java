package com.nutrimarket.nutrimarket.auth;

import com.nutrimarket.nutrimarket.config.JwtProperties;
import com.nutrimarket.nutrimarket.dao.auth.UserMapper;

import com.nutrimarket.nutrimarket.dao.dietary.IntakeRepository;
import com.nutrimarket.nutrimarket.model.dietary.DietaryModels.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=", "debug=false",
        "spring.datasource.url=${test.database.url:jdbc:h2:mem:auth-tests;MODE=MySQL;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${test.database.username:sa}", "spring.datasource.password=${test.database.password:}",
        "spring.sql.init.schema-locations=${test.database.schema:classpath:schema-h2.sql}",
        "nutrimarket.dietary.delivery-delay-ms=3600000"})
class AuthIntegrationTests {
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired JwtProperties properties;
    @Autowired IntakeRepository records;
    @Value("${spring.sql.init.schema-locations}") org.springframework.core.io.Resource schema;
    void initializeSchema() {
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(schema).execute(jdbc.getDataSource());
    }
    final HttpClient http=HttpClient.newHttpClient();
    static final String PASSWORD="Testing-password-123!";

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM dietary_outbox");
        jdbc.update("DELETE FROM dietary_record");
        jdbc.update("DELETE FROM member_account");
    }
    HttpRequest request(String method,String path,String body,String authorization) {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(authorization!=null) builder.header("Authorization",authorization);
        if(body!=null) builder.header("Content-Type","application/json");
        return builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
    }
    HttpResponse<String> send(String method,String path,String body,String authorization) throws Exception {
        return http.send(request(method,path,body,authorization),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> credentials(String action,String username,String password) throws Exception {
        return send("POST","/api/v1/auth/"+action,json.writeValueAsString(Map.of("username",username,"password",password)),null);
    }
    JsonNode register(String username) throws Exception {
        var response=credentials("register",username,PASSWORD);
        assertThat(response.statusCode()).isEqualTo(201); return json.readTree(response.body());
    }
    String login(String username) throws Exception {
        var response=credentials("login",username,PASSWORD);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("accessToken").asString();
    }
    HttpResponse<String> me(String token) throws Exception { return send("GET","/api/v1/auth/me",null,"Bearer "+token); }
    String customToken(String userId,String issuer,List<String> audience,Instant issued,Instant expires,String tokenUse) {
        var claims=JwtClaimsSet.builder().subject(userId).issuedAt(issued).claim("token_use",tokenUse).claim("identity_type","member_id");
        if(issuer!=null) claims.issuer(issuer);
        if(audience!=null) claims.audience(audience);
        if(expires!=null) claims.expiresAt(expires);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims.build())).getTokenValue();
    }

    @Test void registerPersistsOnlyPasswordHashAndReturnsPublicUser() throws Exception {
        var view=register("  ALICE  ");
        var user=users.findByUsername("alice");
        assertThat(user).isNotNull(); assertThat(user.username()).isEqualTo(view.path("username").asString());
        assertThat(user.passwordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD,user.passwordHash())).isTrue();
        assertThat(view.toString()).doesNotContain("password","hash",PASSWORD);
        assertThat(UUID.fromString(view.path("id").asString()).toString()).isEqualTo(user.id());
        assertThat(view.has("email")).isFalse();
    }
    @Test void htmlPagesAndScriptsArePublicButMemberDataStillRequiresLogin() throws Exception {
        for(String path:List.of("/","/index.html","/login.html","/register.html","/upload.html","/record.html","/profile.html","/health.html","/assessment.html","/expert.html")) {
            var response=send("GET",path,null,null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html");
            assertThat(response.body()).contains("NutriMarket");
        }
        for(String path:List.of("/api.js","/auth.js","/home.js","/upload.js","/upload-crypto.js","/upload-photo.js","/health.js","/health-ui.js","/profile.js","/assessment.js","/expert.js","/record.js","/dietary-ui.js","/style.css"))
            assertThat(send("GET",path,null,null).statusCode()).isEqualTo(200);
        for(String path:List.of("/api/v1/auth/me","/api/v1/dietary-records"))
            assertThat(send("GET",path,null,null).statusCode()).isEqualTo(401);
    }
    // A page whose module imports a script the security rules do not list fails to load as a whole, so every shipped file must be public.
    @Test void everyShippedPageScriptAndStylesheetIsPublic() throws Exception {
        try(var files=java.nio.file.Files.list(java.nio.file.Path.of("src/main/resources/static"))) {
            var names=files.map(f->f.getFileName().toString()).filter(n->n.endsWith(".html")||n.endsWith(".js")||n.endsWith(".css")).sorted().toList();
            assertThat(names).contains("body-ui.js","address-ui.js");
            for(String name:names)assertThat(send("GET","/"+name,null,null).statusCode()).as(name).isEqualTo(200);
        }
    }
    @Test void defaultUserCanLoginWithConfiguredPasswordAndReceiveJwt() throws Exception {
        initializeSchema();
        var admin=users.findByUsername("admin");
        assertThat(admin).isNotNull();
        assertThat(admin.passwordHash()).isNotEqualTo("elec5620");
        assertThat(passwords.matches("elec5620",admin.passwordHash())).isTrue();
        var response=credentials("login","admin","elec5620");
        assertThat(response.statusCode()).isEqualTo(200);
        var token=json.readTree(response.body()).path("accessToken").asString();
        assertThat(decoder.decode(token).getSubject()).isEqualTo(admin.id());
        assertThat(me(token).statusCode()).isEqualTo(200);
        assertThat(credentials("login","admin","wrong").statusCode()).isEqualTo(401);
        assertThat(credentials("register","another_user","5620").statusCode()).isEqualTo(400);
    }
    @Test void repeatedSchemaInitializationResetsOnlyAdminAndPreservesCreationTime() throws Exception {
        register("admin");
        register("alice");
        var alice=users.findByUsername("alice");
        jdbc.update("UPDATE member_account SET enabled=FALSE WHERE username='admin'");
        var before=users.findByUsername("admin");
        initializeSchema();
        initializeSchema();
        var after=users.findByUsername("admin");
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(after.enabled()).isTrue();
        assertThat(credentials("login","admin","elec5620").statusCode()).isEqualTo(200);
        assertThat(credentials("login","admin","5620").statusCode()).isEqualTo(401);
        assertThat(users.findByUsername("alice")).isEqualTo(alice);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_account WHERE username='admin'",Integer.class)).isEqualTo(1);
    }
    @Test void memberIdIsTheOnlyDatabasePrimaryKey() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            var keys=new ArrayList<String>();
            String table=connection.getMetaData().getDatabaseProductName().equals("H2")?"MEMBER_ACCOUNT":"member_account";
            try(var result=connection.getMetaData().getPrimaryKeys(connection.getCatalog(),null,table)) {
                while(result.next()) keys.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            assertThat(keys).containsExactly("id");
        }
    }
    @Test void usernamesEnforceBoundariesAndOldEmailRequestIsRejected() throws Exception {
        for(String username:List.of("ab","a".repeat(33),"123alice","alice@example.com","alice smith","us\u00e9r"))
            assertThat(credentials("register",username,PASSWORD).statusCode()).isEqualTo(400);
        assertThat(credentials("register","a".repeat(32),PASSWORD).statusCode()).isEqualTo(201);
        assertThat(send("POST","/api/v1/auth/login",json.writeValueAsString(Map.of("email","alice@example.com","password",PASSWORD)),null)
                .statusCode()).isEqualTo(400);
    }
    @Test void previousUsernameIdentityTokenIsRejected() throws Exception {
        register("alice");
        var claims=JwtClaimsSet.builder().subject("alice").issuer(properties.issuer()).audience(List.of(properties.audience()))
                .issuedAt(Instant.now().minusSeconds(1)).expiresAt(Instant.now().plusSeconds(900)).claim("token_use","access").claim("identity_type","username").build();
        String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        assertThat(me(token).statusCode()).isEqualTo(401);
    }
    @Test void loginReturnsJwtAndMeUsesStableUserId() throws Exception {
        var user=register("alice");
        var response=credentials("login"," ALICE ",PASSWORD);
        assertThat(response.statusCode()).isEqualTo(200);
        var body=json.readTree(response.body());
        assertThat(body.path("tokenType").asString()).isEqualTo("Bearer");
        assertThat(body.path("expiresIn").asLong()).isEqualTo(900);
        var token=body.path("accessToken").asString(); var jwt=decoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo(user.path("id").asString());
        assertThat(jwt.getClaims()).doesNotContainKeys("password","passwordHash","email");
        assertThat(me(token).statusCode()).isEqualTo(200);
        assertThat(json.readTree(me(token).body()).path("username").asString()).isEqualTo("alice");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }
    @Test void duplicateUsernamesIgnoreCaseAndDoNotOverwritePassword() throws Exception {
        register("alice");
        var response=credentials("register","ALICE","Different-password-123!");
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("USERNAME_ALREADY_REGISTERED");
        assertThat(passwords.matches(PASSWORD,users.findByUsername("alice").passwordHash())).isTrue();
    }
    @Test void concurrentRegistrationIsProtectedByDatabaseUniqueConstraint() throws Exception {
        var body=json.writeValueAsString(Map.of("username","alice","password",PASSWORD));
        var first=http.sendAsync(request("POST","/api/v1/auth/register",body,null),HttpResponse.BodyHandlers.ofString());
        var second=http.sendAsync(request("POST","/api/v1/auth/register",body,null),HttpResponse.BodyHandlers.ofString());
        assertThat(List.of(first.get().statusCode(),second.get().statusCode())).containsExactlyInAnyOrder(201,409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_account",Integer.class)).isEqualTo(1);
    }
    @Test void wrongPasswordAndUnknownUsernameHaveSameError() throws Exception {
        register("alice");
        for(String username:List.of("alice","missing_user")) {
            var response=credentials("login",username,"Incorrect-password-123!");
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("INVALID_CREDENTIALS");
            assertThat(json.readTree(response.body()).path("message").asString()).isEqualTo("Incorrect username or password.");
        }
    }
    @Test void invalidUsernameWeakPasswordAndUnicodeByteOverflowAreRejected() throws Exception {
        for(var input:List.of(Map.of("username","bad-name!","password",PASSWORD),
                Map.of("username","alice","password","short"),
                Map.of("username","alice","password","é".repeat(37)))) {
            assertThat(credentials("register",input.get("username"),input.get("password")).statusCode()).isEqualTo(400);
        }
        assertThat(send("POST","/api/v1/auth/register","{}",null).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_account",Integer.class)).isZero();
    }
    @Test void missingTokenBasicCredentialsAndMalformedTokenAreUnauthorized() throws Exception {
        for(String path:List.of("/api/v1/auth/me","/api/v1/dietary-records")) {
            for(String auth:Arrays.asList(null,"Basic bWVtYmVyLTAwMTpsb2NhbC1kZW1vLW9ubHk=","Bearer not-a-jwt")) {
                var response=send("GET",path,null,auth);
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("UNAUTHORIZED");
            }
        }
    }
    @Test void tamperedSignatureIsRejected() throws Exception {
        register("alice"); var token=login("alice");
        int start=token.lastIndexOf('.')+1;
        String altered=token.substring(0,start)+(token.charAt(start)=='A'?'B':'A')+token.substring(start+1);
        assertThat(me(altered).statusCode()).isEqualTo(401);
    }
    @Test void expiredTokenIsRejected() throws Exception {
        String id=register("alice").path("id").asString();
        String token=customToken(id,properties.issuer(),List.of(properties.audience()),Instant.now().minusSeconds(120),Instant.now().minusSeconds(60),"access");
        assertThat(me(token).statusCode()).isEqualTo(401);
    }
    @Test void wrongOrMissingClaimsAreRejectedEvenWithValidSignature() throws Exception {
        String id=register("alice").path("id").asString();
        Instant now=Instant.now().minusSeconds(1), expiry=now.plusSeconds(900);
        var tokens=List.of(customToken(id,"other-issuer",List.of(properties.audience()),now,expiry,"access"),
                customToken(id,properties.issuer(),List.of("other-audience"),now,expiry,"access"),
                customToken(id,properties.issuer(),null,now,expiry,"access"),
                customToken(id,properties.issuer(),List.of(properties.audience()),now,null,"access"),
                customToken(id,properties.issuer(),List.of(properties.audience()),now,expiry,"refresh"),
                customToken("member-001",properties.issuer(),List.of(properties.audience()),now,expiry,"access"));
        for(String token:tokens) assertThat(me(token).statusCode()).isEqualTo(401);
    }
    @Test void unsignedAlgorithmNoneIsRejected() throws Exception {
        register("alice"); var token=login("alice");
        String header=Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        String unsigned=header+"."+token.split("\\.")[1]+".";
        assertThat(me(unsigned).statusCode()).isEqualTo(401);
    }
    @Test void disabledOrDeletedAccountCannotUseIssuedToken() throws Exception {
        register("alice"); String token=login("alice");
        jdbc.update("UPDATE member_account SET enabled=FALSE");
        assertThat(me(token).statusCode()).isEqualTo(401);
        assertThat(credentials("login","alice",PASSWORD).statusCode()).isEqualTo(401);
        jdbc.update("DELETE FROM member_account");
        assertThat(me(token).statusCode()).isEqualTo(401);
    }
    @Test void userCannotReadOrMutateAnotherUsersDietaryRecord() throws Exception {
        String aliceId=register("alice").path("id").asString();
        register("bob"); String aliceToken=login("alice"), bobToken=login("bob");
        var record=new IntakeRecord(); record.recordId=UUID.randomUUID(); record.memberId=aliceId;
        record.profileVersion=1; record.createdAt=Instant.now(); record.mealTime=Instant.now();
        record.status=Status.PENDING_MEMBER_CONFIRMATION; record.expiresAt=Instant.now().plusSeconds(3600);
        records.insert(record,"test-fingerprint",new byte[]{1},"image/png");
        String path="/api/v1/dietary-records/"+record.recordId;
        assertThat(send("GET",path,null,"Bearer "+aliceToken).statusCode()).isEqualTo(200);
        assertThat(send("GET",path,null,"Bearer "+bobToken).statusCode()).isEqualTo(404);
        assertThat(send("POST",path+"/confirm",null,"Bearer "+bobToken).statusCode()).isEqualTo(404);
        assertThat(send("POST",path+"/abandon",null,"Bearer "+bobToken).statusCode()).isEqualTo(404);
        var list=send("GET","/api/v1/dietary-records",null,"Bearer "+bobToken);
        assertThat(json.readTree(list.body()).path("total").asLong()).isZero();
        assertThat(records.find(record.recordId).orElseThrow().record().status).isEqualTo(Status.PENDING_MEMBER_CONFIRMATION);
        assertThatThrownBy(()->jdbc.update("DELETE FROM member_account WHERE id=?",aliceId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE dietary_record SET member_id=? WHERE record_id=?",
                UUID.randomUUID().toString(),record.recordId.toString()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void changingUsernameDoesNotChangeJwtIdentityOrRecordOwnership() throws Exception {
        String id=register("alice").path("id").asString();
        String token=login("alice");
        jdbc.update("UPDATE member_account SET username='alice_new' WHERE id=?",id);
        var response=me(token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("id").asString()).isEqualTo(id);
        assertThat(json.readTree(response.body()).path("username").asString()).isEqualTo("alice_new");
        assertThat(decoder.decode(login("alice_new")).getSubject()).isEqualTo(id);
    }
}
