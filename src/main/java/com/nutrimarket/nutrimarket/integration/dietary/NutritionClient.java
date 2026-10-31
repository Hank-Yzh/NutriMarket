package com.nutrimarket.nutrimarket.integration.dietary;

import static com.nutrimarket.nutrimarket.model.dietary.DietaryModels.*;

public interface NutritionClient { NutritionMatch lookup(FoodInput food); }
