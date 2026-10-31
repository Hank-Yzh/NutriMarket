package com.nutrimarket.nutrimarket.integration.market;

import com.nutrimarket.nutrimarket.model.market.MarketModels.*;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.Requirement;
import java.math.BigDecimal;
import java.util.*;
public interface MarketSemanticClient {
    Map<String,BigDecimal> match(Requirement requirement,List<Product> products);
}
