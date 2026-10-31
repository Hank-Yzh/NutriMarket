package com.nutrimarket.nutrimarket.integration.planning;

import com.nutrimarket.nutrimarket.model.planning.PlanningModels.*;
import com.nutrimarket.nutrimarket.model.health.HealthModels.*;
import java.util.*;

public interface PlanningClient {
    Candidate propose(PlanningInput input,String goals,String preferences,Map<String,IngredientSource> catalog,
                      Candidate current,List<String> affectedLines,List<String> feedback);
}
