package com.nutrimarket.nutrimarket.controller.market;

import com.nutrimarket.nutrimarket.model.market.MarketModels.*;
import com.nutrimarket.nutrimarket.service.market.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/market")
@Validated
public class MarketController {
    private final MarketService service;private final CatalogService catalog;
    public MarketController(MarketService service,CatalogService catalog){this.service=service;this.catalog=catalog;}
    @GetMapping("/access") public Map<String,Boolean> access(Principal p){return Map.of("operator",catalog.operator(p.getName()));}
    @GetMapping("/catalogue") public CatalogPage products(@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(50) int size){return catalog.list(page,size);}
    @PostMapping("/catalogue") public Product create(Principal p,@Valid @RequestBody ProductInput input){return catalog.save(p.getName(),null,input);}
    @PutMapping("/catalogue/{id}") public Product save(Principal p,@PathVariable UUID id,@Valid @RequestBody ProductInput input){return catalog.save(p.getName(),id.toString(),input);}
    @PostMapping("/results") public MarketResult start(Principal p,@Valid @RequestBody Start input){return service.start(p.getName(),input);}
    @GetMapping("/results") public ResultPage list(Principal p,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="10") @Min(1) @Max(50) int size){return service.list(p.getName(),page,size);}
    @GetMapping("/results/{id}") public MarketResult get(Principal p,@PathVariable UUID id){return service.get(p.getName(),id.toString());}
    @PostMapping("/results/{id}/refresh") public MarketResult refresh(Principal p,@PathVariable UUID id,@Valid @RequestBody Refresh input){return service.refresh(p.getName(),id.toString(),input);}
    @GetMapping("/results/{id}/package") public ProcurementInputPackage pack(Principal p,@PathVariable UUID id){return service.packageFor(p.getName(),id.toString());}
}
