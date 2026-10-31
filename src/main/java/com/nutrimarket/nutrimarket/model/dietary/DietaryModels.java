package com.nutrimarket.nutrimarket.model.dietary;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class DietaryModels {
    private DietaryModels() {}
    public enum Status { DEFERRED, REPLACEMENT_REQUIRED, PENDING_MEMBER_CONFIRMATION, VALIDATED, ABANDONED, EXPIRED }
    public enum InputMethod { PHOTO, TEXT, PHOTO_AND_TEXT }
    public record FoodInput(@NotBlank @Size(max=200) String name,
                            @NotBlank @Size(max=200) String searchTerm,
                            @NotNull @DecimalMin("0.1") @DecimalMax("10000") BigDecimal portionGrams,
                            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
                            @Positive Long fdcId) {}
    public record Recognition(boolean usable, String reason, List<FoodInput> foods) {}
    public record Nutrient(BigDecimal amount, String unit) {}
    // Keys use USDA nutrient IDs; missing nutrients remain absent, never fabricated as zero.
    public record NutritionMatch(long fdcId, String description, BigDecimal matchConfidence,
                                 Map<String, Nutrient> per100g, Provenance provenance) {}
    public record Provenance(String provider, String reference, String url, Instant retrievedAt) {}
    public record FoodItem(FoodInput estimate, String matchedDescription, long fdcId,
                           BigDecimal matchConfidence, Map<String, Nutrient> nutrients,
                           Provenance provenance) {}
    public record Failure(String code, String message, Instant at) {}
    public record Audit(String action, Instant at) {}
    public record RecordSummary(UUID recordId, long profileVersion, Status status, Instant mealTime,
                                Instant createdAt, BigDecimal confidence, Map<String, Nutrient> nutrients) {
        public static RecordSummary from(IntakeRecord record) {
            return new RecordSummary(record.recordId, record.profileVersion, record.status, record.mealTime,
                    record.createdAt, record.confidence, record.nutrients);
        }
    }
    public record RecordPage(List<RecordSummary> items, long total, int page, int size) {}
    public record Correction(@NotEmpty @Size(max=20) List<@Valid FoodInput> foods) {}

    // Persisted as JSON; photo bytes are stored separately and never serialized to API responses.
    public static class IntakeRecord {
        public int schemaVersion = 1;
        public UUID recordId;
        public String memberId;
        public long profileVersion;
        public String policyVersion;
        public Status status;
        public Instant mealTime;
        public Instant createdAt;
        public Instant analyzedAt;
        public Instant expiresAt;
        public Instant confirmedAt;
        public boolean photoProcessingConsent;
        public InputMethod inputMethod = InputMethod.PHOTO;
        public String mealDescription = "";
        public int attempts;
        public BigDecimal confidence;
        public List<FoodInput> estimates = new ArrayList<>();
        public List<FoodItem> foodItems = new ArrayList<>();
        public Map<String, Nutrient> nutrients = new TreeMap<>();
        public List<Provenance> provenance = new ArrayList<>();
        public List<Failure> failures = new ArrayList<>();
        public List<Audit> audit = new ArrayList<>();
    }
    public record ValidatedDietaryIntakeRecord(int schemaVersion, UUID recordId, String memberId,
            long profileVersion, String policyVersion, Instant timestamp, Instant mealTime,
            Instant analyzedAt, Instant confirmedAt, List<FoodItem> foodItems,
            Map<String, Nutrient> nutrients, BigDecimal confidence, List<Provenance> provenance) {
        public static ValidatedDietaryIntakeRecord from(IntakeRecord r, Instant now) {
            return new ValidatedDietaryIntakeRecord(1, r.recordId, r.memberId, r.profileVersion,
                    r.policyVersion, now, r.mealTime, r.analyzedAt, r.confirmedAt,
                    r.foodItems, r.nutrients, r.confidence, r.provenance);
        }
    }
}
