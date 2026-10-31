package com.nutrimarket.nutrimarket.service.market;

import com.nutrimarket.nutrimarket.config.MarketPolicy;
import com.nutrimarket.nutrimarket.dao.auth.UserMapper;
import com.nutrimarket.nutrimarket.dao.market.MarketMapper;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.integration.market.HttpCatalogueClient;
import com.nutrimarket.nutrimarket.model.market.MarketModels.*;
import com.nutrimarket.nutrimarket.service.planning.IngredientCatalog;
import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class CatalogService {
    private final MarketMapper mapper;private final MarketPolicy policy;private final UserMapper users;private final IngredientCatalog ingredients;
    private final HttpCatalogueClient remote;private final ObjectMapper json;private final Clock clock;private final TransactionTemplate tx;
    public CatalogService(MarketMapper mapper,MarketPolicy policy,UserMapper users,IngredientCatalog ingredients,HttpCatalogueClient remote,ObjectMapper json,Clock clock,TransactionTemplate tx){this.mapper=mapper;this.policy=policy;this.users=users;this.ingredients=ingredients;this.remote=remote;this.json=json;this.clock=clock;this.tx=tx;}
    public boolean operator(String member){var u=users.findById(member);return u!=null&&u.enabled()&&policy.operatorUsernames().contains(u.username());}
    private void requireOperator(String member){if(!operator(member))throw new AgentException(HttpStatus.FORBIDDEN,"OPERATOR_REQUIRED","Catalogue changes require a configured market operator account.");}
    public CatalogPage list(int page,int size){return new CatalogPage(mapper.products(size,(long)(page-1)*size).stream().map(r->json.readValue(r.document(),Product.class)).toList(),page,size);}
    public Product save(String actor,String id,ProductInput input){requireOperator(actor);return store(id,input,users.findById(actor).username(),false);}
    /** System import with a fixed product ID (synthetic test catalogue): same validation and versioned history as operator edits. */
    public Product importProduct(String id,ProductInput input,String source){return store(id,input,source,true);}
    private Product store(String id,ProductInput input,String updatedBy,boolean create){
        if(ingredients.get(input.ingredientId())==null)throw bad("Choose a recognised planning ingredient ID.");
        if(!Set.of("READY_TO_EAT","RAW","UNKNOWN").contains(input.preparation()))throw bad("Preparation must be READY_TO_EAT, RAW or UNKNOWN.");
        if(input.cookedYieldFactor()!=null&&!"RAW".equals(input.preparation()))throw bad("A cooked-yield factor applies only to RAW products.");
        if(input.observedAt().isAfter(clock.instant().plusSeconds(60)))throw bad("The source observation time cannot be in the future.");
        if(input.per100g().values().stream().anyMatch(n->n.amount()==null||n.amount().signum()<0||n.unit()==null||n.unit().isBlank()))throw bad("Nutrition values require non-negative amounts and explicit units.");
        final String productId=id==null?UUID.randomUUID().toString():id;
        return tx.execute(s->{var existing=mapper.product(productId);int expected=existing==null?0:existing.version();if((id!=null&&existing==null&&!create)||input.expectedVersion()!=expected)throw AgentException.conflict("The catalogue product changed. Reload before saving.");
            var product=new Product(productId,expected+1,updatedBy,clock.instant(),input);String doc=json.writeValueAsString(product);
            if(existing==null)mapper.insertProduct(productId,input.ingredientId(),input.enabled(),doc);
            else if(mapper.updateProduct(productId,expected,input.ingredientId(),input.enabled(),doc)!=1)throw AgentException.conflict("The catalogue product changed. Reload before saving.");
            mapper.productHistory(productId,product.version(),doc);return product;});
    }
    public CatalogResult search(String ingredient,String description){
        if(policy.source()==MarketPolicy.Source.HTTP)return remote.search(ingredient,description);
        var rows=mapper.candidates(ingredient,policy.candidateLimit()+1);return new CatalogResult(rows.stream().limit(policy.candidateLimit()).map(r->json.readValue(r.document(),Product.class)).toList(),"Operator-maintained local catalogue",rows.size()>policy.candidateLimit());
    }
    public boolean current(Product p){if(policy.source()!=MarketPolicy.Source.LOCAL)return true;var row=mapper.product(p.productId());return row!=null&&row.version()==p.version();}
    private AgentException bad(String message){return new AgentException(HttpStatus.BAD_REQUEST,"INVALID_CATALOGUE_PRODUCT",message);}
}
