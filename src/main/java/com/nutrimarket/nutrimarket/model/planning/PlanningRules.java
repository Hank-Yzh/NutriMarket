package com.nutrimarket.nutrimarket.model.planning;

import java.util.*;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.Slot;

/** Shared fixed slots and ingredient limits used by prompts and authoritative validation. */
public final class PlanningRules {
    private PlanningRules() {}
    private static final Map<String,Integer> MAX_GRAMS=Map.of("olive_oil",60,"sesame_oil",20,"soy_sauce",20,"garlic",20,"ginger",20,"spring_onion",50,"feta",100);
    public static int maxGrams(String id){return MAX_GRAMS.getOrDefault(id,600);}
    public static List<String> mealLines(){
        List<String> lines=new ArrayList<>();for(int day=0;day<7;day++)for(Slot slot:Slot.values())lines.add("d"+day+"-"+slot);return List.copyOf(lines);
    }
}
