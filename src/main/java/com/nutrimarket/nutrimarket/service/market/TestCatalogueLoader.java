package com.nutrimarket.nutrimarket.service.market;

import com.nutrimarket.nutrimarket.dao.market.MarketMapper;
import com.nutrimarket.nutrimarket.model.market.MarketModels.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the synthetic test catalogue (test-catalogue.json: fictional stores, USDA-derived labels) when
 * nutrimarket.market.test-catalogue=true. Agent 4 only accepts evidence observed within max-age (24 h), so products whose
 * observation is older than 12 h are re-stamped at startup; each change creates a new product version as an operator edit would.
 */
@Component
@ConditionalOnProperty(name="nutrimarket.market.test-catalogue",havingValue="true")
public class TestCatalogueLoader implements ApplicationRunner {
    static final Duration RESTAMP_AFTER=Duration.ofHours(12);
    private static final TypeReference<Map<String,Object>> MAP=new TypeReference<>(){};
    private static final Logger log=LoggerFactory.getLogger(TestCatalogueLoader.class);
    public record Fixture(String productId,ProductInput facts) {}
    private final CatalogService catalog;private final MarketMapper mapper;private final ObjectMapper json;private final Clock clock;
    public TestCatalogueLoader(CatalogService catalog,MarketMapper mapper,ObjectMapper json,Clock clock){this.catalog=catalog;this.mapper=mapper;this.json=json;this.clock=clock;}
    @Override public void run(ApplicationArguments args){load();}
    /** Returns the number of products inserted or updated. */
    public int load(){
        List<Fixture> fixtures;
        try(var in=new ClassPathResource("test-catalogue.json").getInputStream()){fixtures=json.readValue(in,new TypeReference<List<Fixture>>(){});}
        catch(IOException e){throw new IllegalStateException("Cannot read test-catalogue.json",e);}
        Instant now=clock.instant();int changed=0;
        for(var fixture:fixtures){
            var row=mapper.product(fixture.productId());var current=row==null?null:json.readValue(row.document(),Product.class).facts();
            boolean fresh=current!=null&&current.observedAt()!=null&&current.observedAt().plus(RESTAMP_AFTER).isAfter(now);
            if(fresh&&comparable(current).equals(comparable(fixture.facts())))continue;
            try{catalog.importProduct(fixture.productId(),stamped(fixture.facts(),row==null?0:row.version(),now),"test-catalogue");changed++;}
            catch(RuntimeException e){log.warn("Test catalogue product {} was not loaded: {}",fixture.facts().sku(),e.getMessage());}
        }
        log.info("Synthetic test catalogue: {} products, {} inserted or refreshed.",fixtures.size(),changed);return changed;
    }
    private Map<String,Object> comparable(ProductInput p){var map=json.convertValue(p,MAP);map.remove("observedAt");map.remove("expectedVersion");return map;}
    private ProductInput stamped(ProductInput p,int version,Instant now){var map=json.convertValue(p,MAP);map.put("expectedVersion",version);map.put("observedAt",now);return json.convertValue(map,ProductInput.class);}
}
