package com.nutrimarket.nutrimarket.integration.market;

import com.nutrimarket.nutrimarket.config.MarketPolicy;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.integration.http.ProviderHttp;
import com.nutrimarket.nutrimarket.model.market.MarketModels.*;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class HttpCatalogueClient {
    private final MarketPolicy policy;private final ProviderHttp http;private final ObjectMapper json;private final Validator validator;
    public HttpCatalogueClient(MarketPolicy policy,ProviderHttp http,ObjectMapper json,Validator validator){this.policy=policy;this.http=http;this.json=json;this.validator=validator;}
    public CatalogResult search(String ingredient,String description) {
        if(policy.catalogueEndpoint().isBlank())throw AgentException.provider("CATALOGUE_NOT_CONFIGURED","The authorised catalogue endpoint is not configured.");
        var data=http.json("POST",policy.catalogueEndpoint(),policy.catalogueApiKey(),policy.catalogueTimeout(),Map.of("ingredientId",ingredient,"description",description,"limit",policy.candidateLimit()),"CATALOGUE");
        try {
            var result=json.treeToValue(data,CatalogResult.class);
            if(result.products()==null||result.source()==null||result.source().isBlank()||result.products().size()>policy.candidateLimit())throw new IllegalArgumentException();
            Set<String> ids=new HashSet<>();for(var p:result.products())if(p==null||p.productId()==null||p.productId().isBlank()||!ids.add(p.productId())||p.version()<1||p.facts()==null||!validator.validate(p.facts()).isEmpty())throw new IllegalArgumentException();
            return result;
        }catch(RuntimeException e){throw AgentException.provider("CATALOGUE_INVALID_RESPONSE","The catalogue returned incomplete or invalid product records.");}
    }
}
