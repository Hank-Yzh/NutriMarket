package com.nutrimarket.nutrimarket.market;

import com.nutrimarket.nutrimarket.config.*;
import com.nutrimarket.nutrimarket.integration.http.ProviderHttp;
import com.nutrimarket.nutrimarket.integration.market.*;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.model.market.MarketModels.*;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.Requirement;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class MarketProviderContractTests {
    HttpServer server;String root,response;int status=200;JsonMapper json=JsonMapper.builder().build();AtomicReference<String> body=new AtomicReference<>(),auth=new AtomicReference<>();
    @BeforeEach void start()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);root="http://127.0.0.1:"+server.getAddress().getPort();server.createContext("/",e->{auth.set(e.getRequestHeaders().getFirst("Authorization"));body.set(new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();}
    @AfterEach void stop(){server.stop(0);}
    MarketPolicy policy(){return new MarketPolicy("market-v1",MarketPolicy.Source.HTTP,root+"/catalogue","catalogue-fixture",Duration.ofSeconds(2),Duration.ofHours(24),3,20,true,"qwen-plus",Duration.ofSeconds(2),.85,List.of(),"","",Duration.ofSeconds(2),3);}
    Product product(){var facts=new ProductInput(0,"fixture","SKU-01","Brown rice pouch","Fixture","Prepared grains","rice","READY_TO_EAT",null,"Store A","AUD",BigDecimal.TEN,BigDecimal.valueOf(500),"",Availability.IN_STOCK,Instant.now(),"https://example.invalid/evidence",List.of("rice"),List.of(),List.of(),List.of(),true,true,Map.of(),true,true);return new Product("product-1",1,"private-operator",Instant.now(),facts);}
    QwenMarketClient client(String key){return new QwenMarketClient(new AgentConfiguration.Integrations(new AgentConfiguration.Vision(root,"vision-model",key,Duration.ofSeconds(2)),null,null),policy(),new ProviderHttp(json),json);}
    Requirement requirement(){return new Requirement("private-requirement","private-menu","rice","Cooked brown rice",BigDecimal.valueOf(200),"g edible ready-to-eat weight","private-group");}
    void modelResponse(String content){response=json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",content)))));}
    @Test void modelOnlyScoresSuppliedProductsAndReceivesNoPrivateIdentity(){modelResponse("{\"matches\":[{\"productId\":\"product-1\",\"confidence\":0.97}]}");var result=client("model-fixture").match(requirement(),List.of(product()));assertThat(result.get("product-1")).isEqualByComparingTo("0.97");assertThat(auth.get()).isEqualTo("Bearer model-fixture");assertThat(body.get()).contains("qwen-plus","in English","Cooked brown rice","Brown rice pouch").doesNotContain("private-operator","private-menu","private-requirement","private-group","catalogue-fixture","model-fixture","observedAt");}
    @Test void inventedProductIdsAndInvalidConfidenceAreRejected(){for(String match:List.of("{\"productId\":\"invented\",\"confidence\":1}","{\"productId\":\"product-1\",\"confidence\":1.1}")){modelResponse("{\"matches\":["+match+"]}");assertThatThrownBy(()->client("model-fixture").match(requirement(),List.of(product()))).isInstanceOf(AgentException.class).extracting(e->((AgentException)e).code()).isEqualTo("MARKET_MODEL_INVALID_RESPONSE");}}
    @Test void missingModelKeyDoesNotCallNetwork(){assertThatThrownBy(()->client("").match(requirement(),List.of(product()))).isInstanceOf(AgentException.class);assertThat(body.get()).isNull();}
    @Test void authorisedCatalogueAdapterPreservesFactsAndUsesConfiguredCredential(){response=json.writeValueAsString(new CatalogResult(List.of(product()),"Fixture catalogue",false));try(var factory=Validation.buildDefaultValidatorFactory()){var client=new HttpCatalogueClient(policy(),new ProviderHttp(json),json,factory.getValidator());var data=client.search("rice","Cooked brown rice");assertThat(data.products().get(0).facts().price()).isEqualByComparingTo("10");assertThat(auth.get()).isEqualTo("Bearer catalogue-fixture");assertThat(body.get()).contains("ingredientId","rice","limit").doesNotContain("private-operator");}}
    @Test void badCatalogueDataIsExplicitSourceFailure(){response="{\"products\":[{}],\"source\":\"fixture\",\"truncated\":false}";try(var factory=Validation.buildDefaultValidatorFactory()){var client=new HttpCatalogueClient(policy(),new ProviderHttp(json),json,factory.getValidator());assertThatThrownBy(()->client.search("rice","Rice")).isInstanceOf(AgentException.class).extracting(e->((AgentException)e).code()).isEqualTo("CATALOGUE_INVALID_RESPONSE");}}
}
