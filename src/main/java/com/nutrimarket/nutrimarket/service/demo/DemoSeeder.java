package com.nutrimarket.nutrimarket.service.demo;

import com.nutrimarket.nutrimarket.config.HealthPolicy;
import com.nutrimarket.nutrimarket.config.MarketPolicy;
import com.nutrimarket.nutrimarket.dao.auth.UserMapper;
import com.nutrimarket.nutrimarket.dao.health.HealthMapper;
import com.nutrimarket.nutrimarket.model.health.HealthModels.Clearance;
import com.nutrimarket.nutrimarket.service.health.HealthService;
import com.nutrimarket.nutrimarket.service.market.TestCatalogueLoader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Prepares a presentation: restores the demo-data.sql baseline for every demo account (discarding rehearsal changes), then
 * prepares the scenarios in demo-scenarios.json for next week. Scenario members get the shared profile with their own
 * constraints, a copy of demo_ready's meal week and a real Agent 2 assessment; then DemoScenarios runs Agents 3-5 and checks
 * the outcome. Plans are week-specific, so they are generated here rather than stored in SQL. Runs only with
 * nutrimarket.demo.seed=true, then exits with code 1 if any scenario check fails. With nutrimarket.demo.keep-existing=true
 * nothing is deleted: demo-data.sql only adds missing rows, members that already have scenario data are re-checked rather
 * than rebuilt, and other accounts are never touched.
 */
@Component
@ConditionalOnProperty(name="nutrimarket.demo.seed",havingValue="true")
public class DemoSeeder implements ApplicationRunner {
    // Fixed identifiers from demo-data.sql.
    static final String READY="842bbd79-01fe-4f3f-a295-105a216fa3ea";
    static final String ASSESSMENT="cd8d7b6e-bebb-4cd3-899d-19066842ebc1";
    static final ZoneId ZONE=ZoneId.of("Australia/Sydney");
    private static final List<String> BASELINE=List.of("demo_ready","demo_live","demo_review","demo_expert","demo_new");
    private static final Logger log=LoggerFactory.getLogger(DemoSeeder.class);
    private final HealthPolicy healthPolicy;private final MarketPolicy marketPolicy;private final HealthService health;private final DemoScenarios scenarios;
    private final ObjectProvider<TestCatalogueLoader> testCatalogue;private final UserMapper users;private final ObjectMapper json;
    private final JdbcTemplate jdbc;private final DataSource dataSource;private final TransactionTemplate tx;private final Clock clock;
    private final ConfigurableApplicationContext context;private final HealthMapper healthMapper;private final boolean keepExisting;
    public DemoSeeder(HealthPolicy healthPolicy,MarketPolicy marketPolicy,HealthService health,HealthMapper healthMapper,DemoScenarios scenarios,ObjectProvider<TestCatalogueLoader> testCatalogue,
            UserMapper users,ObjectMapper json,JdbcTemplate jdbc,DataSource dataSource,TransactionTemplate tx,Clock clock,ConfigurableApplicationContext context,
            @Value("${nutrimarket.demo.keep-existing:false}") boolean keepExisting) {
        this.healthPolicy=healthPolicy;this.marketPolicy=marketPolicy;this.health=health;this.healthMapper=healthMapper;this.scenarios=scenarios;this.testCatalogue=testCatalogue;
        this.users=users;this.json=json;this.jdbc=jdbc;this.dataSource=dataSource;this.tx=tx;this.clock=clock;this.context=context;this.keepExisting=keepExisting;
    }
    @Override public void run(ApplicationArguments args) {
        int code=0;
        try {seed();}
        catch(RuntimeException e) {code=1;log.error("Demo preparation failed: {}",e.getMessage(),e);}
        int exit=code;System.exit(SpringApplication.exit(context,()->exit));
    }
    private void seed() {
        if(!healthPolicy.expertUsernames().contains("demo_expert")) throw new IllegalStateException("Add demo_expert to nutrimarket.health.expert-usernames in config/api-keys.yml first.");
        // The scenarios depend on the synthetic catalogue's stock and allergen evidence; refresh it before anything is matched.
        var loader=testCatalogue.getIfAvailable();
        if(loader==null) throw new IllegalStateException("Set nutrimarket.market.test-catalogue: true in config/api-keys.yml; the demo scenarios use the synthetic test catalogue.");
        loader.load();
        var fixture=scenarios.fixture();
        var usernames=Stream.concat(BASELINE.stream(),fixture.members().stream().map(DemoScenarios.Member::username)).distinct().toList();
        if(!keepExisting)for(String username:usernames){var user=users.findByUsername(username);if(user!=null)delete(user.id());}
        var populator=new ResourceDatabasePopulator(new ClassPathResource("demo-data.sql"));populator.setSqlScriptEncoding("UTF-8");
        DatabasePopulatorUtils.execute(populator,dataSource);
        Map<String,String> ids=new LinkedHashMap<>();
        for(String username:usernames){var user=users.findByUsername(username);if(user==null) throw new IllegalStateException("demo-data.sql did not restore account "+username+".");ids.put(username,user.id());}
        log.info(keepExisting?"Kept existing data; demo-data.sql added any missing rows for {} demo accounts.":"Restored {} demo accounts from demo-data.sql.",ids.size());
        LocalDate week=clock.instant().atZone(ZONE).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1);
        log.info("Planning week {}. Product matching uses {}.",week,marketPolicy.semanticEnabled()?"Qwen semantic scoring":"the verified catalogue mapping (semantic scoring disabled)");
        int failed=0;
        for(var m:fixture.members()) {
            String member=ids.get(m.username());DemoScenarios.Run run;
            try {run=scenarios.run(member,m,READY.equals(member)?ASSESSMENT:assessment(member,m),week);}
            catch(RuntimeException e) {
                failed++;log.error("{} {} - {}: NOT PREPARED: {}{}",m.code(),m.username(),m.scenario(),e.getMessage(),keepExisting?" Its existing data may be stale or changed; rerun without keep-existing to rebuild the demo accounts.":"");continue;
            }
            List<DemoScenarios.Check> checks=new ArrayList<>(run.checks());
            if("ORDER_PLACED".equals(m.expect())&&run.passed())checks.addAll(scenarios.verifyOrder(scenarios.order(member,run.basket())));
            boolean passed=checks.stream().allMatch(DemoScenarios.Check::passed);if(!passed)failed++;
            log.info("{} {} - {}: {} (plan {}, market {}, basket {} {})",m.code(),m.username(),m.scenario(),passed?"PASS":"FAIL",run.plan().planId,run.market().state,run.basket().basketId,run.basket().state);
            for(var c:checks)log.info("  [{}] {}: {}",c.passed()?"PASS":"FAIL",c.description(),c.evidence());
        }
        if(failed>0) throw new IllegalStateException(failed+" demo scenario(s) failed their checks; see the log above.");
        log.info("Demo ready for week {}. demo_review: meals only, start live from the assessment. demo_new: no profile yet.",week);
    }
    /** The member's cleared assessment of the demo week; prepared on first use, and reused when keeping existing data. */
    private String assessment(String member,DemoScenarios.Member m) {
        if(keepExisting&&healthMapper.profile(member)!=null) {
            var row=healthMapper.latestCleared(member);
            if(row==null||!row.periodStart().equals(scenarios.fixture().assessmentWeek())) throw new IllegalStateException("the account already has a profile but no cleared assessment of the demo week.");
            return row.assessmentId();
        }
        return assess(member,m);
    }
    /** Shared profile with the member's constraints, a copy of demo_ready's meal week and a real Agent 2 assessment of it. */
    private String assess(String member,DemoScenarios.Member m) {
        health.saveProfile(member,scenarios.profile(m,0));
        copyMeals(READY,member);
        var a=health.generate(member,scenarios.fixture().assessmentWeek());
        if(a.planningClearance!=Clearance.CLEARED) throw new IllegalStateException(m.code()+" assessment is "+a.planningClearance+": "+a.reasons);
        return a.assessmentId;
    }
    // Validated meals, their delivery receipt and Agent 2's intake copy, re-keyed for the new member.
    private void copyMeals(String from,String to) {
        tx.executeWithoutResult(s->{
            for(var row:jdbc.queryForList("SELECT record_id,fingerprint,status,created_at,expires_at,document,media_type FROM dietary_record WHERE member_id=?",from)) {
                String source=(String)row.get("record_id"),id=UUID.nameUUIDFromBytes((to+"/"+source).getBytes(StandardCharsets.UTF_8)).toString();
                jdbc.update("INSERT INTO dietary_record (record_id,member_id,fingerprint,fingerprint_member_id,status,created_at,expires_at,document,photo,media_type) VALUES (?,?,?,?,?,?,?,?,NULL,?)",
                    id,to,row.get("fingerprint"),to,row.get("status"),row.get("created_at"),row.get("expires_at"),rekey((String)row.get("document"),id,to),row.get("media_type"));
                for(var outbox:jdbc.queryForList("SELECT payload,delivered,attempts,updated_at FROM dietary_outbox WHERE record_id=?",source))
                    jdbc.update("INSERT INTO dietary_outbox (record_id,payload,delivered,attempts,updated_at) VALUES (?,?,?,?,?)",id,rekey((String)outbox.get("payload"),id,to),outbox.get("delivered"),outbox.get("attempts"),outbox.get("updated_at"));
                for(var intake:jdbc.queryForList("SELECT profile_version,meal_time,document FROM health_intake WHERE record_id=?",source))
                    jdbc.update("INSERT INTO health_intake (record_id,member_id,profile_version,meal_time,document) VALUES (?,?,?,?,?)",id,to,intake.get("profile_version"),intake.get("meal_time"),rekey((String)intake.get("document"),id,to));
            }
        });
    }
    private String rekey(String document,String recordId,String member) {
        var node=(ObjectNode)json.readTree(document);node.put("recordId",recordId);node.put("memberId",member);return json.writeValueAsString(node);
    }
    // Removes the demo account and every row that references it, children first; demo-data.sql recreates the baseline.
    private void delete(String member) {
        tx.executeWithoutResult(s->{
            jdbc.update("DELETE FROM member_pantry WHERE member_id=?",member);
            jdbc.update("DELETE FROM member_delivery_address WHERE member_id=?",member);
            jdbc.update("DELETE FROM member_food_dislike WHERE member_id=?",member);
            jdbc.update("DELETE FROM procurement_order WHERE member_id=?",member);
            jdbc.update("DELETE FROM shopping_basket WHERE member_id=?",member);
            jdbc.update("DELETE FROM market_outbox WHERE result_id IN (SELECT result_id FROM market_result WHERE member_id=?)",member);
            jdbc.update("DELETE FROM market_result WHERE member_id=?",member);
            jdbc.update("DELETE FROM planning_outbox WHERE plan_id IN (SELECT plan_id FROM nutrition_plan WHERE member_id=?)",member);
            jdbc.update("DELETE FROM nutrition_plan WHERE member_id=?",member);
            jdbc.update("DELETE FROM weekly_health_assessment WHERE member_id=?",member);
            jdbc.update("DELETE FROM health_intake WHERE member_id=?",member);
            jdbc.update("DELETE FROM member_health_profile WHERE member_id=?",member);
            jdbc.update("DELETE FROM dietary_outbox WHERE record_id IN (SELECT record_id FROM dietary_record WHERE member_id=?)",member);
            jdbc.update("DELETE FROM dietary_record WHERE member_id=?",member);
            jdbc.update("DELETE FROM member_account WHERE id=?",member);
        });
    }
}
