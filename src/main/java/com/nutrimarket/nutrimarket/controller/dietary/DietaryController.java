package com.nutrimarket.nutrimarket.controller.dietary;

import com.nutrimarket.nutrimarket.service.dietary.DietaryAnalysisService;
import com.nutrimarket.nutrimarket.service.dietary.IntakeDelivery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.nutrimarket.nutrimarket.model.dietary.DietaryModels.*;

@RestController
@RequestMapping("/api/v1/dietary-records")
@Validated
public class DietaryController {
    private final DietaryAnalysisService service;
    private final IntakeDelivery delivery;
    public DietaryController(DietaryAnalysisService service, IntakeDelivery delivery) { this.service=service; this.delivery=delivery; }
    @GetMapping
    public RecordPage list(Principal p, @RequestParam(defaultValue="1") @Min(1) int page,
                           @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
                           @RequestParam(required=false) Status status) {
        return service.list(p.getName(),status,page,size);
    }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public IntakeRecord analyze(Principal principal, @RequestParam UUID recordId,
            @RequestParam @Positive long profileVersion, @RequestParam Instant mealTime,
            @RequestParam boolean photoProcessingConsent,
            @RequestPart(value="photo",required=false) MultipartFile photo,
            @RequestParam(defaultValue="") @Size(max=3000) String mealDescription) throws IOException {
        return service.submit(recordId,principal.getName(),profileVersion,mealTime,photoProcessingConsent,
                photo==null?null:photo.getBytes(),mealDescription);
    }
    @GetMapping("/{id}") public IntakeRecord get(Principal p,@PathVariable UUID id) { return service.get(id,p.getName()); }
    @PostMapping("/{id}/retry") public IntakeRecord retry(Principal p,@PathVariable UUID id) { return service.retry(id,p.getName()); }
    @PostMapping("/{id}/confirm") public IntakeRecord confirm(Principal p,@PathVariable UUID id) { return service.confirm(id,p.getName()); }
    @PutMapping("/{id}/foods") public IntakeRecord correct(Principal p,@PathVariable UUID id,@Valid @RequestBody Correction correction) {
        return service.correct(id,p.getName(),correction);
    }
    @PostMapping("/{id}/abandon") public IntakeRecord abandon(Principal p,@PathVariable UUID id) { return service.abandon(id,p.getName()); }
    @GetMapping("/{id}/validated") public ValidatedDietaryIntakeRecord validated(Principal p,@PathVariable UUID id) {
        return service.validated(id,p.getName());
    }
    @GetMapping("/{id}/delivery") public IntakeDelivery.DeliveryStatus delivery(Principal p,@PathVariable UUID id) {
        service.validated(id,p.getName()); return delivery.status(id);
    }
}
