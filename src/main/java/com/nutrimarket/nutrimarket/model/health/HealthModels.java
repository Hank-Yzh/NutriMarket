package com.nutrimarket.nutrimarket.model.health;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class HealthModels {
    private HealthModels() {}
    public record Target(@NotNull @DecimalMin("0.01") @DecimalMax("100000") BigDecimal minimum,
                         @NotNull @DecimalMin("0.01") @DecimalMax("100000") BigDecimal maximum,
                         @NotBlank String unit) {}
    public record ProfileInput(@Min(0) long expectedVersion, @NotBlank @Size(max=80) String timezone,
        @NotBlank @Size(max=500) String goals,
        @NotNull @Size(max=30) List<@NotBlank @Size(max=100) String> allergies,
        @NotNull @Size(max=30) List<@NotBlank @Size(max=100) String> restrictions,
        @NotNull @Size(max=30) List<@NotBlank @Size(max=100) String> conditions,
        @NotNull @Size(max=1000) String preferences,
        @Size(max=500) String targetSource,
        @Size(min=4,max=8) Map<String,@NotNull @Valid Target> targets,
        boolean aiSummaryConsent,
        // Set by the server when Qwen generated the targets from the goals; null for defaults or member-supplied targets.
        @Size(max=1000) String targetRationale,
        // Standing consent to plan meals with Qwen and build a basket after each cleared assessment, without further clicks.
        // Nullable so requests and stored profiles from before this consent existed read as "off".
        Boolean autoPlanningConsent,
        // Preferred meal styles for planning, such as "Asian" or "High-protein"; null means no preference.
        @Size(max=8) List<@NotBlank @Size(max=40) String> mealStyles,
        // Optional body measurements for calculating energy and protein targets; null when not given.
        @Valid Body body) {
        public boolean automaticPlanning(){return Boolean.TRUE.equals(autoPlanningConsent);}
        public ProfileInput(long expectedVersion,String timezone,String goals,List<String> allergies,List<String> restrictions,List<String> conditions,
                            String preferences,String targetSource,Map<String,Target> targets,boolean aiSummaryConsent) {
            this(expectedVersion,timezone,goals,allergies,restrictions,conditions,preferences,targetSource,targets,aiSummaryConsent,null,false,null,null);
        }
        public ProfileInput(long expectedVersion,String timezone,String goals,List<String> allergies,List<String> restrictions,List<String> conditions,
                            String preferences,String targetSource,Map<String,Target> targets,boolean aiSummaryConsent,String targetRationale,Boolean autoPlanningConsent,List<String> mealStyles) {
            this(expectedVersion,timezone,goals,allergies,restrictions,conditions,preferences,targetSource,targets,aiSummaryConsent,targetRationale,autoPlanningConsent,mealStyles,null);
        }
    }
    /** Height, weight and year of birth are given together; sex and activity level are optional. */
    public record Body(@DecimalMin("100") @DecimalMax("250") BigDecimal heightCm,@DecimalMin("30") @DecimalMax("300") BigDecimal weightKg,
                       @Min(1900) @Max(2100) Integer birthYear,@Pattern(regexp="FEMALE|MALE") String sex,
                       @Pattern(regexp="SEDENTARY|LIGHT|MODERATE|ACTIVE|VERY_ACTIVE") String activityLevel) {}
    public record Profile(String memberId,long version,Instant createdAt,ProfileInput information) {}
    public record GenerateRequest(@NotNull LocalDate periodStart,Boolean allowIncomplete) {}
    public record IntakeCoverage(LocalDate periodStart,LocalDate periodEnd,String timezone,int intakeCount,
                                 int observedDays,int coveredDays,int mealsPerDay,int missingMeals,List<LocalDate> incompleteDays) {
        public boolean requiresConfirmation() {return missingMeals>0;}
    }
    public enum AssessmentStatus { INSUFFICIENT_INTAKE_DATA, COMPLETED }
    public enum Clearance { NOT_READY, CLEARED, BLOCKED_FOR_EXPERT_REVIEW, NOT_ELIGIBLE_FOR_AUTOMATION, REFER_TO_HEALTHCARE }
    public enum Risk { NOT_ASSESSED, WITHIN_TARGETS, TARGET_DEVIATION, POTENTIAL_HIGH_RISK, LIMITED_DATA }
    public enum Decision { CLEARED_FOR_PLANNING, MODIFIED_AND_CLEARED, NOT_ELIGIBLE_FOR_AUTOMATION, REFER_TO_HEALTHCARE }
    public record ReviewInput(@NotNull Decision decision,@NotBlank @Size(max=2000) String rationale,
                             @NotNull @Size(max=30) List<@NotBlank @Size(max=200) String> additionalConstraints) {}
    public record ExpertReview(String expertId,String expertUsername,Decision decision,String rationale,
                               List<String> additionalConstraints,Instant decidedAt) {}
    public record Constraints(List<String> excludedAllergens,List<String> dietaryRestrictions,
                              List<String> relevantConditions,Map<String,Target> dailyTargets,List<String> expertInstructions) {}
    public record Comparison(String nutrientId,String name,String unit,BigDecimal total,BigDecimal dailyAverage,
                             BigDecimal minimum,BigDecimal maximum,String finding,int belowDays,int aboveDays,int observedMeals) {}
    public static class Assessment {
        public int schemaVersion=1;
        public String assessmentId,memberId,timezone,policyVersion;
        public long profileVersion;
        public LocalDate periodStart,periodEnd;
        public Instant createdAt,updatedAt;
        public AssessmentStatus status;
        public Clearance planningClearance;
        public Risk riskStatus;
        public boolean stale;
        public boolean incompleteRecords,partialDataAccepted;
        public int observedDays,missingMeals;
        public int intakeCount,coveredDays,requiredDays,requiredMealsPerDay,revision=1;
        public List<String> recordIds=new ArrayList<>(),reasons=new ArrayList<>();
        public List<Comparison> comparisons=new ArrayList<>();
        public Constraints healthConstraintSet;
        public String targetSource,targetRationale;
        public ExpertReview expertDecision;
        public String aiExplanation,aiModel;
        public Instant aiGeneratedAt;
    }
    public record AssessmentPage(List<Assessment> items,int page,int size) {}
    public record ReviewPage(List<Assessment> items,int page,int size,long total) {}
    public record PlanningInput(int schemaVersion,String assessmentId,String memberId,long profileVersion,
                                LocalDate periodStart,LocalDate periodEnd,Clearance planningClearance,
                                Constraints healthConstraintSet,ExpertReview expertDecision,List<Comparison> comparisons,
                                String policyVersion,Instant releasedAt,boolean incompleteRecords,int observedDays,int intakeCount) {}
}
