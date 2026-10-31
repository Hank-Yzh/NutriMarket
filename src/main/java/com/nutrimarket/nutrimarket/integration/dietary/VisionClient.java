package com.nutrimarket.nutrimarket.integration.dietary;

import static com.nutrimarket.nutrimarket.model.dietary.DietaryModels.*;

public interface VisionClient {
    Recognition analyze(byte[] image, String mediaType);
    Recognition analyze(byte[] image, String mediaType, String mealDescription);
}
