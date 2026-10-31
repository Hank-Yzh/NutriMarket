package com.nutrimarket.nutrimarket.service.dietary;

import com.nutrimarket.nutrimarket.config.AgentConfiguration.*;
import com.nutrimarket.nutrimarket.dao.dietary.IntakeRepository;
import com.nutrimarket.nutrimarket.dao.health.HealthMapper;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.integration.dietary.NutritionClient;
import com.nutrimarket.nutrimarket.integration.dietary.VisionClient;
import com.nutrimarket.nutrimarket.validation.ImageValidator;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import static com.nutrimarket.nutrimarket.model.dietary.DietaryModels.*;

/** Single-instance prototype: synchronized operations include transaction commit, so retries cannot race confirmation. */
@Service
public class DietaryAnalysisService {
    private final IntakeRepository repository;
    private final ImageValidator images;
    private final VisionClient vision;
    private final NutritionClient nutrition;
    private final Policy policy;
    private final Integrations integrations;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final HealthMapper healthProfiles;
    public DietaryAnalysisService(IntakeRepository repository, ImageValidator images, VisionClient vision,
            NutritionClient nutrition, Policy policy, Integrations integrations, Clock clock, TransactionTemplate tx, HealthMapper healthProfiles) {
        this.repository=repository; this.images=images; this.vision=vision; this.nutrition=nutrition;
        this.policy=policy; this.integrations=integrations; this.clock=clock; this.tx=tx; this.healthProfiles=healthProfiles;
    }
    public synchronized IntakeRecord submit(UUID id, String member, long version, Instant mealTime, boolean consent, byte[] photo) {
        return submit(id,member,version,mealTime,consent,photo,"");
    }
    public synchronized IntakeRecord submit(UUID id, String member, long version, Instant mealTime, boolean consent, byte[] photo, String mealDescription) {
        if(!consent) throw new AgentException(HttpStatus.BAD_REQUEST,"CONSENT_REQUIRED","Please consent to meal analysis first.");
        String description=mealDescription==null?"":mealDescription.strip();
        boolean hasPhoto=photo!=null && photo.length>0;
        if(description.length()>3000) throw new AgentException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","The meal description must not exceed 3000 characters.");
        if(version<1 || mealTime==null || mealTime.isAfter(clock.instant().plusSeconds(300)))
            throw new AgentException(HttpStatus.BAD_REQUEST,"INVALID_METADATA","The profile version must be positive and the meal time must not be in the future.");
        String fingerprint=fingerprint(member,version,mealTime,photo,description);
        return tx.execute(s->{
            var existing=repository.find(id);
            if(existing.isPresent()) {
                var record=owned(existing.get().record(),member);
                if(!existing.get().fingerprint().equals(fingerprint(existing.get().fingerprintMemberId(),version,mealTime,photo,description)))
                    throw new AgentException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","The same recordId has been used for a different request. Use a new UUID.");
                return expire(record);
            }
            var profile=healthProfiles.profile(member);
            if(profile!=null && profile.profileVersion()!=version)
                throw new AgentException(HttpStatus.CONFLICT,"PROFILE_VERSION_CONFLICT","Your health profile version has changed. Refresh the upload page to use the current version.");
            if(!hasPhoto && description.isEmpty()) throw new AgentException(HttpStatus.BAD_REQUEST,"MEAL_INPUT_REQUIRED","Add a meal photo or describe what you ate.");
            String mediaType=hasPhoto?images.validate(photo):"text/plain";
            var r=new IntakeRecord(); r.recordId=id; r.memberId=member; r.profileVersion=version;
            r.mealTime=mealTime; r.createdAt=clock.instant(); r.photoProcessingConsent=true;
            r.mealDescription=description;
            r.inputMethod=hasPhoto?(description.isEmpty()?InputMethod.PHOTO:InputMethod.PHOTO_AND_TEXT):InputMethod.TEXT;
            r.policyVersion=policy.policyVersion(); r.status=Status.DEFERRED;
            r.audit.add(new Audit("SUBMITTED",clock.instant()));
            repository.insert(r,fingerprint,photo,mediaType);
            analyze(r,photo,mediaType);
            repository.save(r); return r;
        });
    }
    public synchronized IntakeRecord get(UUID id,String member) {
        return tx.execute(s->expire(load(id,member).record()));
    }
    public synchronized RecordPage list(String member, Status status, int page, int size) {
        if(page<1 || size<1 || size>100)
            throw new AgentException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","page must be at least 1 and size must be between 1 and 100.");
        return tx.execute(s->{
            Instant now=clock.instant();
            long total=repository.count(member,status,now);
            var items=repository.findPage(member,status,now,page,size).stream()
                    .map(this::expire).map(RecordSummary::from).toList();
            return new RecordPage(items,total,page,size);
        });
    }
    public synchronized IntakeRecord retry(UUID id,String member) {
        return tx.execute(s->{
            var stored=load(id,member); var r=expire(stored.record());
            if(r.status!=Status.DEFERRED) throw AgentException.conflict("Only DEFERRED records can be retried.");
            if(r.attempts>=policy.maxAttempts()) throw AgentException.conflict("The analysis retry limit has been reached. Check the service configuration.");
            analyze(r,stored.photo(),stored.mediaType()); repository.save(r); return r;
        });
    }
    public synchronized IntakeRecord confirm(UUID id,String member) {
        return tx.execute(s->{
            var r=expire(load(id,member).record());
            if(r.status==Status.VALIDATED) return r;
            if(r.status!=Status.PENDING_MEMBER_CONFIRMATION) throw AgentException.conflict("This record does not yet have nutrition results ready for confirmation.");
            r.confirmedAt=clock.instant(); validate(r); repository.save(r); return r;
        });
    }
    public synchronized IntakeRecord correct(UUID id,String member,Correction correction) {
        return tx.execute(s->{
            var stored=load(id,member); var r=expire(stored.record());
            if(r.status!=Status.PENDING_MEMBER_CONFIRMATION && r.status!=Status.DEFERRED)
                throw AgentException.conflict("Only pending or deferred records can be corrected.");
            if(r.estimates.isEmpty()) throw AgentException.conflict("Complete meal recognition before correcting food details.");
            r.estimates=new ArrayList<>(correction.foods()); r.foodItems.clear(); r.nutrients.clear();
            r.confirmedAt=null; r.confidence=null; r.analyzedAt=null; r.attempts=0; r.expiresAt=null;
            r.audit.add(new Audit("MEMBER_CORRECTED",clock.instant()));
            analyze(r,stored.photo(),stored.mediaType());
            // Corrections are previews: require explicit confirmation even if confidence is high.
            repository.save(r); return r;
        });
    }
    public synchronized IntakeRecord abandon(UUID id,String member) {
        return tx.execute(s->{
            var r=expire(load(id,member).record());
            if(r.status==Status.ABANDONED) return r;
            if(r.status==Status.VALIDATED || r.status==Status.EXPIRED) throw AgentException.conflict("This record is final and cannot be abandoned.");
            r.status=Status.ABANDONED; r.audit.add(new Audit("ABANDONED",clock.instant())); repository.save(r); return r;
        });
    }
    public synchronized ValidatedDietaryIntakeRecord validated(UUID id,String member) {
        if(get(id,member).status!=Status.VALIDATED) throw AgentException.conflict("Only VALIDATED records can be sent to the second agent.");
        return repository.payload(id);
    }
    private void analyze(IntakeRecord r,byte[] photo,String mediaType) {
        r.attempts++; r.audit.add(new Audit("ANALYSIS_ATTEMPT_"+r.attempts,clock.instant()));
        try {
            if(r.estimates.isEmpty()) {
                var result=r.mealDescription.isEmpty()?vision.analyze(photo,mediaType):vision.analyze(photo,mediaType,r.mealDescription);
                r.provenance.add(new Provenance("Alibaba Cloud Qwen-VL",integrations.vision().model(),
                        integrations.vision().baseUrl(),clock.instant()));
                if(!r.mealDescription.isEmpty() && r.provenance.stream().noneMatch(p->p.provider().equals("Member meal description")))
                    r.provenance.add(new Provenance("Member meal description",
                            r.inputMethod==InputMethod.TEXT?"Member-reported meal; no meal photo was provided":"Member-provided context for the meal photo",null,clock.instant()));
                if(!result.usable()) {
                    r.status=Status.REPLACEMENT_REQUIRED;
                    r.failures.add(new Failure(r.inputMethod==InputMethod.TEXT?"UNUSABLE_DESCRIPTION":"UNUSABLE_IMAGE", result.reason(),clock.instant())); return;
                }
                r.estimates=new ArrayList<>(result.foods());
            }
            var items=new ArrayList<FoodItem>();
            BigDecimal confidence=BigDecimal.ONE;
            for(var input:r.estimates) {
                var match=nutrition.lookup(input);
                Map<String,Nutrient> scaled=new TreeMap<>();
                match.per100g().forEach((key,value)->scaled.put(key,new Nutrient(
                        value.amount().multiply(input.portionGrams()).divide(new BigDecimal("100"),6,RoundingMode.HALF_UP),value.unit())));
                items.add(new FoodItem(input,match.description(),match.fdcId(),match.matchConfidence(),scaled,match.provenance()));
                confidence=confidence.min(input.confidence()).min(match.matchConfidence());
            }
            r.foodItems=items; r.confidence=confidence; r.nutrients=total(items); r.analyzedAt=clock.instant();
            r.provenance.removeIf(p->p.provider().equals("USDA FoodData Central"));
            items.forEach(item->r.provenance.add(item.provenance()));
            boolean corrected=r.audit.stream().anyMatch(a->a.action().equals("MEMBER_CORRECTED"));
            if(!corrected && r.inputMethod!=InputMethod.TEXT && confidence.compareTo(BigDecimal.valueOf(policy.confidenceThreshold()))>=0) validate(r);
            else {
                r.status=Status.PENDING_MEMBER_CONFIRMATION; r.expiresAt=clock.instant().plus(policy.confirmationTtl());
                r.audit.add(new Audit("AWAITING_CONFIRMATION",clock.instant()));
            }
        } catch(AgentException e) {
            r.status=Status.DEFERRED; r.failures.add(new Failure(e.code(),e.getMessage(),clock.instant()));
        }
    }
    private Map<String,Nutrient> total(List<FoodItem> items) {
        Map<String,Nutrient> total=new TreeMap<>(items.get(0).nutrients());
        // Only total nutrients present for every ingredient; a missing micronutrient is not zero.
        for(int i=1;i<items.size();i++) {
            var values=items.get(i).nutrients(); total.keySet().retainAll(values.keySet());
            total.entrySet().removeIf(e->!e.getValue().unit().equals(values.get(e.getKey()).unit()));
            total.replaceAll((key,value)->new Nutrient(value.amount().add(values.get(key).amount()),value.unit()));
        }
        return total;
    }
    private void validate(IntakeRecord r) {
        r.status=Status.VALIDATED; r.expiresAt=null; r.audit.add(new Audit("VALIDATED",clock.instant()));
        repository.publish(ValidatedDietaryIntakeRecord.from(r,clock.instant()));
    }
    private IntakeRecord expire(IntakeRecord r) {
        if(r.status==Status.PENDING_MEMBER_CONFIRMATION && r.expiresAt!=null && !clock.instant().isBefore(r.expiresAt)) {
            r.status=Status.EXPIRED; r.audit.add(new Audit("EXPIRED",clock.instant())); repository.save(r);
        }
        return r;
    }
    private IntakeRepository.Stored load(UUID id,String member) {
        var stored=repository.find(id).orElseThrow(()->new AgentException(HttpStatus.NOT_FOUND,"NOT_FOUND","Record not found."));
        owned(stored.record(),member); return stored;
    }
    private IntakeRecord owned(IntakeRecord r,String member) {
        if(!r.memberId.equals(member)) throw new AgentException(HttpStatus.NOT_FOUND,"NOT_FOUND","Record not found.");
        return r;
    }
    private String fingerprint(String member,long version,Instant mealTime,byte[] photo,String description) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            digest.update((member+"\n"+version+"\n"+mealTime+"\n").getBytes(StandardCharsets.UTF_8));
            if(!description.isEmpty()) {
                byte[] text=description.getBytes(StandardCharsets.UTF_8);
                digest.update(("description:"+text.length+":").getBytes(StandardCharsets.UTF_8));
                digest.update(text);
                digest.update((byte)'\n');
            }
            return HexFormat.of().formatHex(digest.digest(photo==null?new byte[0]:photo));
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
