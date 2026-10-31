package com.nutrimarket.nutrimarket.service.planning;

import com.nutrimarket.nutrimarket.model.planning.PlanningModels.Ingredient;
import com.nutrimarket.nutrimarket.model.health.HealthModels.Constraints;
import java.util.*;
import org.springframework.stereotype.Service;

/** Recipe ingredients only. Retail composition and cross-contact require separate SKU evidence. */
@Service
public class IngredientCatalog {
    private static Ingredient food(String id,String name,String search,long fdcId,boolean vegan,boolean vegetarian,String... allergens) {
        return new Ingredient(id,name,search,fdcId,Set.of(allergens),vegan,vegetarian);
    }
    // Pinned SR Legacy fdcIds: a free-text search can return raw or dry forms (e.g. dry oats, raw rice) for cooked ingredients.
    private final List<Ingredient> ingredients=List.of(
        food("oats","Cooked oatmeal","Cereals, oats, regular and quick, not fortified, cooked with water, without salt",173905,true,true,"gluten"),
        food("rice","Cooked brown rice","Rice, brown, long-grain, cooked",169704,true,true),
        food("potato","Boiled potato","Potatoes, boiled, cooked in skin, flesh, without salt",170438,true,true),
        food("chicken","Roasted chicken breast","Chicken, broilers or fryers, breast, meat only, cooked, roasted",171477,false,false),
        food("tofu","Firm tofu","Tofu, raw, firm, prepared with calcium sulfate",172475,true,true,"soy"),
        food("lentils","Cooked lentils","Lentils, mature seeds, cooked, boiled, without salt",172421,true,true),
        food("egg","Hard-boiled egg","Egg, whole, cooked, hard-boiled",173424,false,true,"egg"),
        food("yogurt","Plain low-fat yogurt","Yogurt, plain, low fat, 12 grams protein per 8 ounce",170886,false,true,"milk"),
        food("broccoli","Cooked broccoli","Broccoli, cooked, boiled, drained, without salt",169967,true,true),
        food("carrot","Cooked carrot","Carrots, cooked, boiled, drained, without salt",170394,true,true),
        food("spinach","Cooked spinach","Spinach, cooked, boiled, drained, without salt",168463,true,true),
        food("banana","Banana","Bananas, raw",173944,true,true),
        food("apple","Apple with skin","Apples, raw, with skin",171688,true,true),
        food("olive_oil","Olive oil","Oil, olive, salad or cooking",171413,true,true),
        // Wider range for Asian, Mediterranean and high-protein meal styles.
        food("white_rice","Steamed white rice","Rice, white, long-grain, regular, enriched, cooked",168878,true,true),
        food("rice_noodles","Cooked rice noodles","Rice noodles, cooked",168914,true,true),
        food("pasta","Cooked wholemeal pasta","Pasta, whole-wheat, cooked (Includes foods for USDA's Food Distribution Program)",168910,true,true,"gluten"),
        food("quinoa","Cooked quinoa","Quinoa, cooked",168917,true,true),
        food("sweet_potato","Baked sweet potato","Sweet potato, cooked, baked in skin, flesh, without salt",168483,true,true),
        food("wholemeal_bread","Wholemeal bread","Bread, whole-wheat, commercially prepared",172688,false,true,"gluten"),
        food("salmon","Baked salmon","Fish, salmon, Atlantic, farmed, cooked, dry heat",175168,false,false,"fish"),
        food("prawns","Cooked prawns","Crustaceans, shrimp, cooked",175180,false,false,"shellfish"),
        food("beef","Braised lean beef","Beef, chuck for stew, separable lean and fat, choice, cooked, braised",171205,false,false),
        food("chickpeas","Cooked chickpeas","Chickpeas (garbanzo beans, bengal gram), mature seeds, cooked, boiled, without salt",173757,true,true),
        food("edamame","Cooked edamame","Edamame, frozen, prepared",168411,true,true,"soy"),
        food("feta","Feta cheese","Cheese, feta",173420,false,true,"milk"),
        food("bok_choy","Cooked bok choy","Cabbage, chinese (pak-choi), cooked, boiled, drained, without salt",170391,true,true),
        food("mushrooms","Cooked shiitake mushrooms","Mushrooms, shiitake, cooked, without salt",168437,true,true),
        food("tomato","Fresh tomato","Tomatoes, red, ripe, raw, year round average",170457,true,true),
        food("cucumber","Cucumber","Cucumber, with peel, raw",168409,true,true),
        food("capsicum","Red capsicum","Peppers, sweet, red, raw",170108,true,true),
        food("avocado","Avocado","Avocados, raw, all commercial varieties",171705,true,true),
        food("soy_sauce","Soy sauce","Soy sauce made from soy and wheat (shoyu)",174277,true,true,"soy","gluten"),
        food("sesame_oil","Sesame oil","Oil, sesame, salad or cooking",171016,true,true,"sesame"),
        food("garlic","Garlic","Garlic, raw",169230,true,true),
        food("ginger","Fresh ginger","Ginger root, raw",169231,true,true),
        food("spring_onion","Spring onion","Onions, spring or scallions (includes tops and bulb), raw",170005,true,true));
    // Per-meal gram ceilings for oils and seasonings; other ingredients allow up to 600 g.
    private static final Map<String,Integer> MAX_GRAMS=Map.of("olive_oil",60,"sesame_oil",20,"soy_sauce",20,"garlic",20,"ginger",20,"spring_onion",50,"feta",100);
    public int maxGrams(String id) { return MAX_GRAMS.getOrDefault(id,600); }
    // Shelf-stable staples whose unopened or dry leftovers keep for later weeks. Oils and sauces keep once opened.
    private static final Set<String> STAPLES=Set.of("oats","rice","white_rice","lentils","pasta","quinoa","rice_noodles","olive_oil","sesame_oil","soy_sauce");
    private static final Set<String> CONDIMENTS=Set.of("olive_oil","sesame_oil","soy_sauce");
    /** Whether leftovers of this product form are kept in the pantry: dry staples sold raw, or bottled oils and sauces. */
    public boolean keepsInPantry(String id,String preparation) { return STAPLES.contains(id)&&("RAW".equals(preparation)||CONDIMENTS.contains(id)); }
    // Foods with a similar role in a meal, in order of preference. The planner keeps only those that fit the day's targets.
    private static final Map<String,List<String>> ALTERNATIVES=Map.ofEntries(
        Map.entry("rice",List.of("white_rice","quinoa","rice_noodles")),Map.entry("white_rice",List.of("rice","quinoa","rice_noodles")),
        Map.entry("quinoa",List.of("rice","white_rice")),Map.entry("rice_noodles",List.of("white_rice","pasta")),Map.entry("pasta",List.of("rice_noodles","quinoa")),
        Map.entry("potato",List.of("sweet_potato")),Map.entry("sweet_potato",List.of("potato")),
        Map.entry("oats",List.of("wholemeal_bread")),Map.entry("wholemeal_bread",List.of("oats")),
        Map.entry("chicken",List.of("beef","tofu","prawns")),Map.entry("beef",List.of("chicken","tofu")),
        Map.entry("salmon",List.of("prawns","chicken","tofu")),Map.entry("prawns",List.of("salmon","chicken","tofu")),
        Map.entry("tofu",List.of("edamame","chickpeas","egg")),Map.entry("edamame",List.of("tofu","chickpeas")),
        Map.entry("lentils",List.of("chickpeas")),Map.entry("chickpeas",List.of("lentils")),Map.entry("egg",List.of("tofu","yogurt")),Map.entry("yogurt",List.of("egg")),
        Map.entry("broccoli",List.of("bok_choy","spinach")),Map.entry("bok_choy",List.of("broccoli","spinach")),Map.entry("spinach",List.of("bok_choy","broccoli")),
        Map.entry("carrot",List.of("capsicum","broccoli")),Map.entry("capsicum",List.of("carrot","tomato")),Map.entry("tomato",List.of("capsicum","cucumber")),
        Map.entry("cucumber",List.of("tomato","capsicum")),Map.entry("mushrooms",List.of("bok_choy","broccoli")),
        Map.entry("banana",List.of("apple")),Map.entry("apple",List.of("banana")),Map.entry("olive_oil",List.of("sesame_oil")),Map.entry("sesame_oil",List.of("olive_oil")));
    public List<String> alternatives(String id) { return ALTERNATIVES.getOrDefault(id,List.of()); }
    public List<Ingredient> all() { return ingredients; }
    public Ingredient get(String id) { return ingredients.stream().filter(i->i.id().equals(id)).findFirst().orElse(null); }
    // Retail search names are independent of the preparation-specific USDA nutrition reference.
    // Do not strip cooking words heuristically: dry grains, raw meat and processed foods differ.
    private static final Map<String,String> PURCHASE_NAMES=Map.ofEntries(
        Map.entry("oats","Rolled oats"),Map.entry("rice","Brown rice"),Map.entry("potato","Potatoes"),
        Map.entry("chicken","Chicken breast"),Map.entry("lentils","Dried lentils"),Map.entry("egg","Eggs"),
        Map.entry("broccoli","Broccoli"),Map.entry("carrot","Carrots"),Map.entry("spinach","Spinach"),
        Map.entry("white_rice","White rice"),Map.entry("rice_noodles","Dry rice noodles"),
        Map.entry("pasta","Dry wholemeal pasta"),Map.entry("quinoa","Dry quinoa"),
        Map.entry("sweet_potato","Sweet potatoes"),Map.entry("salmon","Salmon fillets"),
        Map.entry("prawns","Raw prawns"),Map.entry("beef","Lean beef for stewing"),
        Map.entry("chickpeas","Dried chickpeas"),Map.entry("edamame","Frozen edamame"),
        Map.entry("bok_choy","Bok choy"),Map.entry("mushrooms","Shiitake mushrooms"));
    public String purchaseName(String id) {
        var ingredient=get(id);
        return PURCHASE_NAMES.getOrDefault(id,ingredient==null?id:ingredient.name());
    }
    /** Keeps legacy published packages searchable without changing their meal quantities or evidence. */
    public com.nutrimarket.nutrimarket.model.planning.PlanningModels.Requirement purchaseRequirement(
            com.nutrimarket.nutrimarket.model.planning.PlanningModels.Requirement r) {
        if(r==null||r.ingredientId()==null)return r;
        return new com.nutrimarket.nutrimarket.model.planning.PlanningModels.Requirement(
            r.requirementId(),r.menuLineId(),r.ingredientId(),purchaseName(r.ingredientId()),r.quantity(),r.unit(),r.substitutionGroupId());
    }
    private String normal(String s) {return s.toLowerCase(Locale.ROOT).strip().replaceAll("[-_ ]+"," ");}
    /**
     * What a product's ingredient list must show for each catalog food: terms naming the food (singular; plurals also match),
     * carriers allowed before it (tofu and soy sauce start with water), and the minimum stated share for single foods.
     * A product named after a food but made mostly of something else, such as apple lollies or tomato sauce, fails.
     */
    private record Composition(List<String> terms,Set<String> carriers,Integer minShare) {}
    private static Composition single(String... terms){return new Composition(List.of(terms),Set.of(),90);}
    private static final Map<String,Composition> COMPOSITION=Map.ofEntries(
        Map.entry("oats",single("oat")),Map.entry("rice",single("brown rice")),Map.entry("white_rice",single("white rice","jasmine rice","basmati rice")),
        Map.entry("potato",single("potato")),Map.entry("chicken",single("chicken")),Map.entry("tofu",new Composition(List.of("soybean"),Set.of("water"),null)),
        Map.entry("lentils",new Composition(List.of("lentil"),Set.of(),50)),Map.entry("egg",single("egg")),
        Map.entry("yogurt",new Composition(List.of("milk"),Set.of(),null)),Map.entry("broccoli",single("broccoli")),Map.entry("carrot",single("carrot")),
        Map.entry("spinach",single("spinach")),Map.entry("banana",single("banana")),Map.entry("apple",single("apple")),Map.entry("olive_oil",single("olive oil")),
Map.entry("rice_noodles",new Composition(List.of("rice flour","rice noodle"),Set.of(),null)),
        Map.entry("pasta",single("wholemeal durum wheat","wholemeal wheat","whole wheat")),Map.entry("quinoa",single("quinoa")),
        Map.entry("sweet_potato",single("sweet potato")),Map.entry("wholemeal_bread",new Composition(List.of("wholemeal wheat flour","wholemeal flour","whole wheat flour"),Set.of(),null)),
        Map.entry("salmon",single("salmon")),Map.entry("prawns",single("prawn","shrimp")),Map.entry("beef",single("beef")),
        Map.entry("chickpeas",new Composition(List.of("chickpea"),Set.of(),50)),Map.entry("edamame",single("soybean","edamame")),
        Map.entry("feta",new Composition(List.of("milk"),Set.of(),null)),Map.entry("bok_choy",single("bok choy","pak choi")),
        Map.entry("mushrooms",single("mushroom")),Map.entry("tomato",single("tomato")),Map.entry("cucumber",single("cucumber")),
        Map.entry("capsicum",single("capsicum","pepper")),Map.entry("avocado",single("avocado")),
        Map.entry("soy_sauce",new Composition(List.of("soybean"),Set.of("water"),null)),Map.entry("sesame_oil",single("sesame oil")),
        Map.entry("garlic",single("garlic")),Map.entry("ginger",single("ginger")),Map.entry("spring_onion",single("spring onion","scallion")));
    private static boolean mentions(String component,List<String> terms) {
        String text=component.toLowerCase(Locale.ROOT);
        return terms.stream().anyMatch(t->java.util.regex.Pattern.compile("\\b"+java.util.regex.Pattern.quote(t)+"(e?s)?\\b").matcher(text).find());
    }
    /** Ingredient-list entries, split on commas and semicolons outside parentheses. */
    public static List<String> components(List<String> ingredients) {
        List<String> out=new ArrayList<>();
        for(String entry:ingredients){int depth=0;var part=new StringBuilder();
            for(char ch:entry.toCharArray()){if(ch=='(')depth++;if(ch==')')depth=Math.max(0,depth-1);
                if((ch==','||ch==';')&&depth==0){if(!part.toString().isBlank())out.add(part.toString().strip());part.setLength(0);}else part.append(ch);}
            if(!part.toString().isBlank())out.add(part.toString().strip());}
        return out;
    }
    /** Why a product's ingredient list does not confirm it is this food, or null when it does. */
    public String compositionProblem(String id,List<String> ingredients) {
        var spec=COMPOSITION.get(id);if(spec==null)return null;
        var parts=components(ingredients);String food=purchaseName(id).toLowerCase(Locale.ROOT);
        if(parts.isEmpty())return "No ingredient list confirms this is "+food+".";
        int at=-1;for(int i=0;i<parts.size();i++)if(mentions(parts.get(i),spec.terms())){at=i;break;}
        if(at<0)return "The ingredient list does not contain "+food+".";
        for(int i=0;i<at;i++)if(!spec.carriers().contains(normal(parts.get(i)).replaceAll("\\(.*\\)","").strip()))return "The main ingredient is \""+parts.get(0)+"\", not "+food+".";
        var share=java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%").matcher(parts.get(at));
        if(spec.minShare()!=null&&share.find()&&Double.parseDouble(share.group(1))<spec.minShare())return food.substring(0,1).toUpperCase(Locale.ROOT)+food.substring(1)+" is only "+share.group(1)+"% of the product.";
        return null;
    }
    /** Whether any ingredient-list entry contains this catalog food. */
    public boolean contains(String id,List<String> ingredients) {
        var spec=COMPOSITION.get(id);return spec!=null&&components(ingredients).stream().anyMatch(c->mentions(c,spec.terms()));
    }
    /** The catalog food a free-text entry names ("apple", "no apples", "avoid garlic"), or null. */
    public String ingredientNamed(String text) {
        String name=normal(text).replaceFirst("^(no|avoid|without|not) ","").replaceFirst(" (free|allergy)$","");
        for(String form:List.of(name,name.replaceFirst("es$",""),name.replaceFirst("s$",""))) {
            if(get(form.replace(' ','_'))!=null)return form.replace(' ','_');
            // A word shared by several foods ("milk" for yogurt and feta) is ambiguous, so it stays unrecognised for an expert.
            var named=ingredients.stream().filter(i->i.name().toLowerCase(Locale.ROOT).equals(form)||purchaseName(i.id()).toLowerCase(Locale.ROOT).equals(form)
                ||COMPOSITION.containsKey(i.id())&&COMPOSITION.get(i.id()).terms().contains(form)).map(Ingredient::id).distinct().toList();
            if(named.size()==1)return named.get(0);
        }
        return null;
    }
    /** Catalog foods the member's allergies or dietary restrictions name directly, such as "apple". */
    public Set<String> excludedFoods(Constraints c) {
        Set<String> out=new TreeSet<>();
        for(String a:c.excludedAllergens())for(String tag:allergens(a))if(get(tag)!=null)out.add(tag);
        for(String r:c.dietaryRestrictions()){var id=ingredientNamed(r);if(id!=null)out.add(id);}
        return out;
    }
    // Accepts "no seafood", "seafood allergy" and "nut-free" as well as the bare allergen.
    private Set<String> allergens(String text) {
        String name=normal(text).replaceFirst("^(no|avoid) ","").replaceFirst(" (free|allergy)$","");
        return switch(name) {
            case "peanut","peanuts" -> Set.of("peanut");
            case "nut","nuts","tree nuts","tree nut" -> Set.of("tree_nut");
            case "milk","dairy","lactose" -> Set.of("milk");
            case "egg","eggs" -> Set.of("egg");
            case "soy","soya","soybeans" -> Set.of("soy");
            case "wheat","gluten" -> Set.of("gluten");
            case "fish" -> Set.of("fish");
            case "shellfish","crustaceans","molluscs","mollusks" -> Set.of("shellfish");
            case "seafood" -> Set.of("fish","shellfish");
            case "sesame" -> Set.of("sesame");
            default -> {var id=ingredientNamed(name);yield id==null?Set.of():Set.of(id);}
        };
    }
    /** Allergies and dietary restrictions that planning cannot enforce; Agent 2 routes these to expert review. */
    public List<String> unrecognised(List<String> allergies,List<String> restrictions) {
        List<String> result=new ArrayList<>();
        for(String a:allergies) if(allergens(a).isEmpty()) result.add(a);
        for(String r:restrictions) if(!knownRestriction(r)) result.add(r);
        return result;
    }
    /** reviewConditions: recorded conditions outside the routine list (see HealthEngine.conditionsRequiringReview). */
    public List<String> manualChecks(Constraints c,List<String> reviewConditions) {
        List<String> reasons=new ArrayList<>();
        for(String a:c.excludedAllergens()) if(allergens(a).isEmpty()) reasons.add("An expert must interpret this allergy: "+a);
        for(String r:c.dietaryRestrictions()) if(!knownRestriction(r)) reasons.add("An expert must interpret this dietary restriction: "+r);
        if(!reviewConditions.isEmpty()) reasons.add("An expert must check recipe suitability for: "+String.join(", ",reviewConditions)+".");
        if(!c.expertInstructions().isEmpty()) reasons.add("An expert must verify all assessment-specific instructions against this meal plan.");
        return reasons;
    }
    private boolean knownRestriction(String r) {return Set.of("vegan","vegetarian","pescatarian","dairy free","lactose free","gluten free","nut free","peanut free","soy free","egg free").contains(normal(r))||ingredientNamed(r)!=null;}
    public boolean allowed(Ingredient i,Constraints c) {
        for(String a:c.excludedAllergens()) {
            for(String tag:allergens(a)) if(tag.equals(i.id()) || i.allergens().contains(tag)) return false;
        }
        for(String value:c.dietaryRestrictions()) {
            String r=normal(value);
            boolean seafood=i.allergens().contains("fish")||i.allergens().contains("shellfish");
            if(r.equals("vegan")&&!i.vegan() || r.equals("vegetarian")&&!i.vegetarian() || r.equals("pescatarian")&&!i.vegetarian()&&!seafood)return false;
            if(i.id().equals(ingredientNamed(value)))return false;
            String tag=switch(r){case "dairy free","lactose free"->"milk";case "gluten free"->"gluten";case "nut free"->"tree_nut";case "peanut free"->"peanut";case "soy free"->"soy";case "egg free"->"egg";default->"";};
            if(i.allergens().contains(tag))return false;
        }
        return true;
    }
}
