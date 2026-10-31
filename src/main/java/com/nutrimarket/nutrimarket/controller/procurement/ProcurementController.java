package com.nutrimarket.nutrimarket.controller.procurement;

import com.nutrimarket.nutrimarket.model.procurement.ProcurementModels.*;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.Plan;
import com.nutrimarket.nutrimarket.service.pipeline.MealPipeline;
import com.nutrimarket.nutrimarket.service.procurement.PantryService;
import com.nutrimarket.nutrimarket.service.procurement.ProcurementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/procurement")
@Validated
public class ProcurementController {
    private final ProcurementService service;
    private final MealPipeline pipeline;private final PantryService pantry;
    public ProcurementController(ProcurementService service,MealPipeline pipeline,PantryService pantry){this.service=service;this.pipeline=pipeline;this.pantry=pantry;}
    @GetMapping("/access") public Map<String,Object> access(Principal p){return service.access(p.getName());}
    @GetMapping("/pantry") public PantryView pantry(Principal p){return pantry.view(p.getName());}
    @GetMapping("/address") public AddressView address(Principal p){return service.address(p.getName());}
    @PutMapping("/address") public AddressView saveAddress(Principal p,@Valid @RequestBody DeliveryAddress input){return service.saveAddress(p.getName(),input);}
    @PostMapping("/pantry/corrections") public PantryView correct(Principal p,@Valid @RequestBody PantryAdjustment input){return pantry.correct(p.getName(),input);}
    @GetMapping("/pipeline") public MealPipeline.Status pipeline(Principal p){return pipeline.status(p.getName());}
    @PostMapping("/baskets") public Basket start(Principal p,@Valid @RequestBody Start input){return service.start(p.getName(),input);}
    @GetMapping("/baskets") public BasketPage baskets(Principal p,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="10") @Min(1) @Max(50) int size){return service.list(p.getName(),page,size);}
    @GetMapping("/baskets/{id}") public Basket get(Principal p,@PathVariable UUID id){return service.get(p.getName(),id.toString());}
    @PutMapping("/baskets/{id}/preferences") public Basket change(Principal p,@PathVariable UUID id,@Valid @RequestBody Change input){return service.change(p.getName(),id.toString(),input);}
    @PostMapping("/baskets/{id}/compare") public BasketComparison compare(Principal p,@PathVariable UUID id,@Valid @RequestBody Change input){return service.compare(p.getName(),id.toString(),input);}
    @PostMapping("/baskets/{id}/checkout") public Basket checkout(Principal p,@PathVariable UUID id,@Valid @RequestBody Checkout input){return service.checkout(p.getName(),id.toString(),input);}
    @PostMapping("/baskets/{id}/submit") public SubmitResult submit(Principal p,@PathVariable UUID id,@Valid @RequestBody Checkout input){return service.submit(p.getName(),id.toString(),input);}
    @PostMapping("/baskets/{id}/confirm") public Order confirm(Principal p,@PathVariable UUID id,@Valid @RequestBody Confirm input){return service.confirm(p.getName(),id.toString(),input);}
    @PostMapping("/baskets/{id}/decline") public Basket decline(Principal p,@PathVariable UUID id,@Valid @RequestBody Version input){return service.decline(p.getName(),id.toString(),input);}
    @PostMapping("/baskets/{id}/replan") public Plan replan(Principal p,@PathVariable UUID id,@Valid @RequestBody ReplanInput input){return service.replan(p.getName(),id.toString(),input);}
    @PostMapping("/baskets/{id}/review-request") public Basket escalate(Principal p,@PathVariable UUID id,@Valid @RequestBody Version input){return service.escalate(p.getName(),id.toString(),input);}
    @GetMapping("/orders") public OrderPage orders(Principal p,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="10") @Min(1) @Max(50) int size){return service.orders(p.getName(),page,size);}
    @GetMapping("/orders/{id}") public Order order(Principal p,@PathVariable UUID id){return service.order(p.getName(),id.toString());}
    @PostMapping("/orders/{id}/cancel") public Order cancel(Principal p,@PathVariable UUID id){return service.cancel(p.getName(),id.toString());}
    @PostMapping("/orders/{id}/recover") public Order recover(Principal p,@PathVariable UUID id,@Valid @RequestBody OrderAction input){return service.recover(p.getName(),id.toString(),input);}
    @PostMapping("/orders/{id}/reoptimise") public Basket reoptimise(Principal p,@PathVariable UUID id,@Valid @RequestBody OrderAction input){return service.reoptimise(p.getName(),id.toString(),input);}
    @GetMapping("/expert/queue") public BasketPage queue(Principal p,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="10") @Min(1) @Max(50) int size){return service.queue(p.getName(),page,size);}
    @GetMapping("/expert/baskets/{id}") public Basket detail(Principal p,@PathVariable UUID id){return service.expertDetail(p.getName(),id.toString());}
    @PostMapping("/expert/baskets/{id}/review") public Basket review(Principal p,@PathVariable UUID id,@Valid @RequestBody Review input){return service.review(p.getName(),id.toString(),input);}
    @GetMapping("/reports") public Report report(Principal p){return service.report(p.getName());}
}
