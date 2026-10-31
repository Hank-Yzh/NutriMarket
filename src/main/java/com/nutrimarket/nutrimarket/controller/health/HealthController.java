package com.nutrimarket.nutrimarket.controller.health;

import com.nutrimarket.nutrimarket.service.health.HealthExplanation;
import com.nutrimarket.nutrimarket.service.health.HealthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static com.nutrimarket.nutrimarket.model.health.HealthModels.*;

@RestController
@RequestMapping("/api/v1/health")
@Validated
public class HealthController {
    private final HealthService service;private final HealthExplanation explanation;
    public HealthController(HealthService service,HealthExplanation explanation) {this.service=service;this.explanation=explanation;}
    @GetMapping("/profile") public Profile profile(Principal p) {return service.profile(p.getName());}
    @PutMapping("/profile") public Profile save(Principal p,@Valid @RequestBody ProfileInput input) {return service.saveProfile(p.getName(),input);}
    @GetMapping("/assessment-coverage") public IntakeCoverage coverage(Principal p,@RequestParam java.time.LocalDate periodStart) {return service.coverage(p.getName(),periodStart);}
    @PostMapping("/assessments") public Assessment generate(Principal p,@Valid @RequestBody GenerateRequest input) {return service.generate(p.getName(),input.periodStart(),Boolean.TRUE.equals(input.allowIncomplete()));}
    @GetMapping("/assessments") public AssessmentPage list(Principal p,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="10") @Min(1) @Max(50) int size) {return service.list(p.getName(),page,size);}
    @GetMapping("/assessments/{id}") public Assessment get(Principal p,@PathVariable UUID id) {return service.get(p.getName(),id.toString());}
    @PostMapping("/assessments/{id}/explanation") public Assessment explain(Principal p,@PathVariable UUID id) {return explanation.explain(p.getName(),id.toString());}
    @GetMapping("/assessments/{id}/planning-input") public PlanningInput planning(Principal p,@PathVariable UUID id) {return service.planningInput(p.getName(),id.toString());}
    @GetMapping("/expert/access") public Map<String,Boolean> access(Principal p) {return Map.of("expert",service.isExpert(p.getName()));}
    @GetMapping("/expert/reviewed") public ReviewPage reviewed(Principal p,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="10") @Min(1) @Max(50) int size) {return service.reviewed(p.getName(),page,size);}
    @GetMapping("/expert/queue") public ReviewPage queue(Principal p,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="10") @Min(1) @Max(50) int size) {return service.queue(p.getName(),page,size);}
    @GetMapping("/expert/assessments/{id}") public Map<String,Object> detail(Principal p,@PathVariable UUID id) {return service.expertDetail(p.getName(),id.toString());}
    @PostMapping("/expert/assessments/{id}/review") public Assessment review(Principal p,@PathVariable UUID id,@Valid @RequestBody ReviewInput input) {return service.review(p.getName(),id.toString(),input);}
}
