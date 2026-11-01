package com.nutrimarket.nutrimarket.integration.dietary;

import com.nutrimarket.nutrimarket.config.AgentConfiguration.Integrations;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.integration.http.ProviderHttp;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import static com.nutrimarket.nutrimarket.model.dietary.DietaryModels.*;

@Component
public class UsdaNutritionClient implements NutritionClient {
    private final Integrations config;
    private final ProviderHttp http;
    private final Clock clock;
    private record Cached(NutritionMatch value,Instant expiresAt) {}
    private final ConcurrentHashMap<String,Cached> cache=new ConcurrentHashMap<>();
    private static final Duration CACHE_TTL=Duration.ofHours(24);
    private static final int CACHE_LIMIT=512;
    public UsdaNutritionClient(Integrations config, ProviderHttp http, Clock clock) {
        this.config=config; this.http=http; this.clock=clock;
    }
    @Override public NutritionMatch lookup(FoodInput food) {
        var c=config.nutrition();
        if(c.apiKey().isBlank()) throw AgentException.provider("NUTRITION_NOT_CONFIGURED", "Please configure config/api-keys.yml with your USDA API key.");
        String cacheKey=c.baseUrl()+"|"+(food.fdcId()!=null?"id:"+food.fdcId():"search:"+normalize(food.searchTerm()));
        var cached=cache.compute(cacheKey,(k,old)->old!=null&&clock.instant().isBefore(old.expiresAt())?old:new Cached(fetch(food),clock.instant().plus(CACHE_TTL)));
        if(cache.size()>CACHE_LIMIT){
            cache.entrySet().removeIf(e->!clock.instant().isBefore(e.getValue().expiresAt()));
            cache.entrySet().stream().filter(e->!e.getKey().equals(cacheKey)).min(Comparator.comparing(e->e.getValue().expiresAt())).ifPresent(e->cache.remove(e.getKey(),e.getValue()));
        }
        return cached.value();
    }
    private NutritionMatch fetch(FoodInput food){
        var c=config.nutrition();
        String root=c.baseUrl().replaceAll("/$", ""), key="?api_key="+URLEncoder.encode(c.apiKey(),StandardCharsets.UTF_8);
        long id; BigDecimal confidence;
        if(food.fdcId()!=null) { id=food.fdcId(); confidence=BigDecimal.ONE; }
        else {
            var search=http.json("POST",root+"/foods/search"+key,"",c.timeout(),
                    Map.of("query",food.searchTerm(),"dataType",List.of("Foundation","SR Legacy"),"pageSize",5),"NUTRITION");
            var candidates=search.path("foods");
            if(!candidates.isArray()) throw invalid();
            if(candidates.isEmpty()) throw AgentException.provider("NUTRITION_NOT_FOUND", "No nutrition source found. Correct the food description or specify a USDA fdcId.");
            JsonNode selected=candidates.get(0);
            for(var candidate:candidates) {
                if(normalize(candidate.path("description").asString()).equals(normalize(food.searchTerm()))) { selected=candidate; break; }
            }
            id=selected.path("fdcId").asLong(0);
            confidence=normalize(selected.path("description").asString()).equals(normalize(food.searchTerm()))
                    ?new BigDecimal("0.95"):new BigDecimal("0.60");
        }
        if(id<=0) throw invalid();
        var details=http.json("GET",root+"/food/"+id+key,"",c.timeout(),null,"NUTRITION");
        if(details.path("fdcId").asLong(0)!=id) throw invalid();
        String description=details.path("description").asString("");
        if(description.isBlank() || !details.path("foodNutrients").isArray()) throw invalid();
        Map<String,Nutrient> values=new TreeMap<>();
        for(var entry:details.path("foodNutrients")) {
            var n=entry.path("nutrient");
            String nutrientId=n.path("id").asString(), unit=n.path("unitName").asString("");
            if(!entry.path("amount").isNumber() || unit.isBlank() || nutrientId==null) continue;
            BigDecimal amount=entry.path("amount").decimalValue();
            if(amount.signum()<0) throw invalid();
            values.put(nutrientId,new Nutrient(amount,unit.toLowerCase(Locale.ROOT)));
        }
        // USDA may provide energy under Atwater-specific IDs; normalize to kcal key 1008.
        if(!values.containsKey("1008")) {
            if(values.containsKey("2047")) values.put("1008",values.get("2047"));
            else if(values.containsKey("2048")) values.put("1008",values.get("2048"));
        }
        for(String required:List.of("1008","1003","1004","1005")) {
            var nutrient=values.get(required);
            if(nutrient==null || !nutrient.unit().equals(required.equals("1008")?"kcal":"g"))
                throw AgentException.provider("NUTRITION_INCOMPLETE", "The nutrition source lacks energy or macronutrients. Select another USDA entry.");
        }
        return new NutritionMatch(id,description,confidence,Map.copyOf(values),
                new Provenance("USDA FoodData Central",Long.toString(id),"https://fdc.nal.usda.gov/food-details/"+id+"/nutrients",clock.instant()));
    }
    private String normalize(String s) { return s==null?"":s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
    private AgentException invalid() { return AgentException.provider("NUTRITION_INVALID_RESPONSE", "USDA  returned invalid data. Please try again later."); }
}
