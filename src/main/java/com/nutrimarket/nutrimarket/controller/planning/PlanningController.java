package com.nutrimarket.nutrimarket.controller.planning;

import com.nutrimarket.nutrimarket.model.planning.PlanningModels.*;
import com.nutrimarket.nutrimarket.service.planning.PlanningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/planning")
@Validated
public class PlanningController {
    private final PlanningService service;
    public PlanningController(PlanningService service){this.service=service;}
    @GetMapping("/catalog") public List<Ingredient> catalog(){return service.catalog();}
    @GetMapping("/disliked-foods") public List<FoodDislike> dislikes(Principal p){return service.dislikes(p.getName());}
    @PutMapping("/disliked-foods/{ingredientId}") public List<FoodDislike> dislike(Principal p,@PathVariable @Pattern(regexp="[a-z0-9_]{1,80}") String ingredientId){return service.dislike(p.getName(),List.of(ingredientId));}
    @DeleteMapping("/disliked-foods/{ingredientId}") public List<FoodDislike> undislike(Principal p,@PathVariable @Pattern(regexp="[a-z0-9_]{1,80}") String ingredientId){return service.undislike(p.getName(),ingredientId);}
    @PostMapping("/plans") public Plan generate(Principal p,@Valid @RequestBody Generate input){return service.generate(p.getName(),input);}
    @GetMapping("/plans") public PlanPage list(Principal p,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="10") @Min(1) @Max(50) int size){return service.list(p.getName(),page,size);}
    @GetMapping("/plans/{id}") public Plan get(Principal p,@PathVariable UUID id){return service.get(p.getName(),id.toString());}
    @PostMapping("/plans/{id}/retry") public Plan retry(Principal p,@PathVariable UUID id,@Valid @RequestBody Version input){return service.retry(p.getName(),id.toString(),input);}
    @PostMapping("/plans/{id}/publish") public Plan publish(Principal p,@PathVariable UUID id,@Valid @RequestBody Publish input){return service.publish(p.getName(),id.toString(),input);}
    @GetMapping("/plans/{id}/package") public PlanningPackage pack(Principal p,@PathVariable UUID id){return service.packageFor(p.getName(),id.toString());}
    @PostMapping("/plans/{id}/replan") public Plan replan(Principal p,@PathVariable UUID id,@Valid @RequestBody Replan input){return service.replan(p.getName(),id.toString(),input);}
}
