package com.nutrimarket.nutrimarket.service.health;

import com.nutrimarket.nutrimarket.config.HealthPolicy;
import com.nutrimarket.nutrimarket.dao.auth.UserMapper;
import com.nutrimarket.nutrimarket.dao.health.HealthMapper;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.model.dietary.DietaryModels.ValidatedDietaryIntakeRecord;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.nutrimarket.nutrimarket.model.health.HealthModels.*;

@Service
public class HealthService {
    private final HealthMapper mapper; private final ObjectMapper json;private final TransactionTemplate tx;
    private final HealthInbox inbox;private final HealthEngine engine;private final Clock clock;private final HealthPolicy policy;private final UserMapper users;
    private final HealthTargets targets;
    public HealthService(HealthMapper mapper,ObjectMapper json,TransactionTemplate tx,HealthInbox inbox,HealthEngine engine,Clock clock,HealthPolicy policy,UserMapper users,HealthTargets targets) {
        this.mapper=mapper;this.json=json;this.tx=tx;this.inbox=inbox;this.engine=engine;this.clock=clock;this.policy=policy;this.users=users;this.targets=targets;
    }
    public Profile profile(String member) {
        var row=mapper.profile(member);
        if(row==null) throw new AgentException(HttpStatus.NOT_FOUND,"PROFILE_REQUIRED","Complete your health profile first.");
        return json.readValue(row.document(),Profile.class);
    }
    public Profile saveProfile(String member,ProfileInput input) {
        try {ZoneId.of(input.timezone());} catch(DateTimeException e) {throw bad("Invalid timezone. Use an IANA timezone such as Australia/Sydney.");}
        if((input.targets()==null)!=(input.targetSource()==null)) throw bad("Supply both nutrition targets and their source, or omit both.");
        var body=measured(input.body());LocalDate today=clock.instant().atZone(ZoneId.of(input.timezone())).toLocalDate();
        if(body!=null&&(body.heightCm()!=null||body.weightKg()!=null||body.birthYear()!=null)) {
            if(body.heightCm()==null||body.weightKg()==null||body.birthYear()==null) throw bad("Enter height, weight and year of birth together, or leave all three blank.");
            int age=today.getYear()-body.birthYear();if(age<16||age>100) throw bad("Body measurements are used for adults aged 16 to 100 only.");
        }
        // The model call stays outside the transaction; the version check below rejects a profile that changed meanwhile.
        var stored=mapper.profile(member);var before=stored==null?null:json.readValue(stored.document(),Profile.class).information();
        // With consent Qwen sets targets from the goals (and body measurements when given); without it, complete measurements are used in the standard equation.
        var generated=input.targets()!=null||reusable(input,before)?null:input.aiSummaryConsent()?targets.generate(input.goals(),body,today):targets.calculated(input.goals(),body,today);
        return tx.execute(s->{
            lock(member);var row=mapper.profile(member);long current=row==null?0:row.profileVersion();
            if(current!=input.expectedVersion()) throw AgentException.conflict("Your health profile has changed. Refresh before saving.");
            var previous=row==null?null:json.readValue(row.document(),Profile.class).information();
            var resolved=resolveTargets(input,previous,generated);
            var p=new Profile(member,current+1,clock.instant(),resolved);
            mapper.insertProfile(new HealthMapper.ProfileRow(member,p.version(),json.writeValueAsString(p)));return p;
        });
    }
    // Earlier targets still apply unless the body measurements changed, or they were generated or calculated from goals or a consent choice that has since changed.
    private static boolean reusable(ProfileInput input,ProfileInput previous) {
        return previous!=null && Objects.equals(measured(previous.body()),measured(input.body()))
            && (previous.targetRationale()==null ? !input.aiSummaryConsent() : previous.goals().equals(input.goals())&&previous.aiSummaryConsent()==input.aiSummaryConsent());
    }
    private ProfileInput resolveTargets(ProfileInput input,ProfileInput previous,HealthTargets.Generated generated) {
        var basis=input.targets()!=null?new HealthTargets.Generated(input.targets(),input.targetSource(),null)
                :generated!=null?generated:reusable(input,previous)?new HealthTargets.Generated(previous.targets(),previous.targetSource(),previous.targetRationale()):this.targets.defaults();
        var targets=basis.targets();var source=basis.source();
        if(source.isBlank() || !targets.keySet().containsAll(List.of("1008","1003","1004","1005")))
            throw bad("A target source and daily targets for energy, protein, fat and carbohydrate are required.");
        targets.forEach((id,t)->{
            if(!HealthEngine.UNITS.containsKey(id) || !HealthEngine.UNITS.get(id).equals(t.unit()) || t.minimum().compareTo(t.maximum())>0)
                throw bad("Invalid nutrient target units or minimum exceeds maximum.");
        });
        return new ProfileInput(input.expectedVersion(),input.timezone(),input.goals(),input.allergies(),
                input.restrictions(),input.conditions(),input.preferences(),source,Map.copyOf(targets),input.aiSummaryConsent(),basis.rationale(),input.automaticPlanning(),input.mealStyles()==null?List.of():List.copyOf(input.mealStyles()),measured(input.body()));
    }
    /** The body measurements, or null when none were given. */
    private static Body measured(Body b){return b==null||b.heightCm()==null&&b.weightKg()==null&&b.birthYear()==null&&b.sex()==null&&b.activityLevel()==null?null:b;}
    public Assessment generate(String member,LocalDate start) {
        return generate(member,start,false);
    }
    public IntakeCoverage coverage(String member,LocalDate start) {
        return tx.execute(s->{
            lock(member);var p=profile(member);validatePeriod(p,start);
            return engine.coverage(p,start,periodIntakes(member,p,start));
        });
    }
    private void validatePeriod(Profile p,LocalDate start) {
        LocalDate today=clock.instant().atZone(ZoneId.of(p.information().timezone())).toLocalDate();
        if(start==null || start.isAfter(today.minusDays(7)) || start.isBefore(today.minusYears(2)))
            throw bad("Choose seven completed consecutive dates within the last two years. The period must end before today in your profile timezone.");
    }
    private List<ValidatedDietaryIntakeRecord> periodIntakes(String member,Profile p,LocalDate start) {
        inbox.synchronize(member);ZoneId zone=ZoneId.of(p.information().timezone());
        return mapper.intakes(member,start.atStartOfDay(zone).toInstant(),start.plusDays(7).atStartOfDay(zone).toInstant())
                .stream().map(r->json.readValue(r.document(),ValidatedDietaryIntakeRecord.class)).toList();
    }
    public Assessment generate(String member,LocalDate start,boolean allowIncomplete) {
        return tx.execute(s->{
            lock(member);Profile p=profile(member);
            validatePeriod(p,start);
            var row=mapper.period(member,p.version(),start);
            Assessment previous=row==null?null:decode(row);
            // Fully covered and expert-reviewed snapshots remain immutable. Unreviewed partial reports can incorporate added meals.
            if(previous!=null && previous.status==AssessmentStatus.COMPLETED && (!previous.incompleteRecords || previous.expertDecision!=null)) return previous;
            var inputs=periodIntakes(member,p,start);
            var a=engine.evaluate(p,start,inputs,clock.instant(),allowIncomplete || previous!=null && previous.partialDataAccepted);
            if(previous!=null) {
                a.assessmentId=previous.assessmentId;a.createdAt=previous.createdAt;a.revision=previous.revision+1;
                if(previous.recordIds.equals(a.recordIds) && previous.policyVersion.equals(a.policyVersion) && previous.partialDataAccepted==a.partialDataAccepted) return previous;
                mapper.updateAssessment(a,json.writeValueAsString(a));
            } else mapper.insertAssessment(a,json.writeValueAsString(a));
            return a;
        });
    }
    public Assessment get(String member,String id) {
        var a=find(id);if(!a.memberId.equals(member)) throw notFound();return withFreshness(a);
    }
    public AssessmentPage list(String member,int page,int size) {
        return new AssessmentPage(mapper.list(member,size,(long)(page-1)*size).stream().map(this::decode).map(this::withFreshness).toList(),page,size);
    }
    public boolean isExpert(String actor) {
        var user=users.findById(actor);return user!=null && user.enabled() && policy.expertUsernames().contains(user.username());
    }
    public void requireExpert(String actor) {
        if(!isExpert(actor)) throw new AgentException(HttpStatus.FORBIDDEN,"EXPERT_REQUIRED","This action requires a configured expert account.");
    }
    public ReviewPage queue(String actor,int page,int size) {
        requireExpert(actor);
        return new ReviewPage(mapper.reviewQueue(size,(long)(page-1)*size).stream().map(this::decode).toList(),page,size,mapper.reviewQueueCount());
    }
    public ReviewPage reviewed(String actor,int page,int size) {
        requireExpert(actor);
        return new ReviewPage(mapper.reviewed(size,(long)(page-1)*size).stream().map(this::decode).map(this::withFreshness).toList(),page,size,mapper.reviewedCount());
    }
    public Map<String,Object> expertDetail(String actor,String id) {
        requireExpert(actor);var a=withFreshness(find(id));
        return Map.of("assessment",a,"profile",profile(a.memberId));
    }
    public Assessment review(String actor,String id,ReviewInput input) {
        requireExpert(actor);var initial=find(id);
        if(initial.memberId.equals(actor)) throw new AgentException(HttpStatus.FORBIDDEN,"SELF_REVIEW_FORBIDDEN","You cannot review your own health assessment.");
        return tx.execute(s->{
            lock(initial.memberId);var a=withFreshness(find(id));
            if(a.stale || a.status!=AssessmentStatus.COMPLETED || a.planningClearance!=Clearance.BLOCKED_FOR_EXPERT_REVIEW || a.expertDecision!=null)
                throw AgentException.conflict("This assessment has been updated, has already been reviewed or does not require review. Refresh the list.");
            if(input.decision()==Decision.MODIFIED_AND_CLEARED && input.additionalConstraints().isEmpty()) throw bad("Modified clearance requires additional constraints. Existing allergies and dietary restrictions will be retained.");
            var user=users.findById(actor);
            a.expertDecision=new ExpertReview(actor,user.username(),input.decision(),input.rationale(),List.copyOf(input.additionalConstraints()),clock.instant());
            var c=a.healthConstraintSet;
            a.healthConstraintSet=new Constraints(c.excludedAllergens(),c.dietaryRestrictions(),c.relevantConditions(),c.dailyTargets(),List.copyOf(input.additionalConstraints()));
            a.planningClearance=switch(input.decision()) {
                case CLEARED_FOR_PLANNING,MODIFIED_AND_CLEARED -> Clearance.CLEARED;
                case NOT_ELIGIBLE_FOR_AUTOMATION -> Clearance.NOT_ELIGIBLE_FOR_AUTOMATION;
                case REFER_TO_HEALTHCARE -> Clearance.REFER_TO_HEALTHCARE;
            };
            a.updatedAt=clock.instant();a.revision++;a.aiExplanation=null;a.aiGeneratedAt=null;a.aiModel=null;
            mapper.updateAssessment(a,json.writeValueAsString(a));return a;
        });
    }
    public PlanningInput planningInput(String member,String id) {
        return tx.execute(s->{
            lock(member);var a=get(member,id);
            if(a.stale || a.status!=AssessmentStatus.COMPLETED || a.planningClearance!=Clearance.CLEARED)
                throw AgentException.conflict("A completed, cleared assessment for the current profile is needed. If meals are missing, you can choose to assess the existing records.");
            if(a.riskStatus==Risk.POTENTIAL_HIGH_RISK && (a.expertDecision==null || !Set.of(Decision.CLEARED_FOR_PLANNING,Decision.MODIFIED_AND_CLEARED).contains(a.expertDecision.decision())))
                throw AgentException.conflict("This high-risk assessment has no valid expert clearance decision.");
            return new PlanningInput(1,a.assessmentId,a.memberId,a.profileVersion,a.periodStart,a.periodEnd,a.planningClearance,a.healthConstraintSet,a.expertDecision,a.comparisons,a.policyVersion,clock.instant(),a.incompleteRecords,a.observedDays,a.intakeCount);
        });
    }
    public Assessment storeExplanation(String member,String id,int revision,String explanation,String model) {
        return tx.execute(s->{
            lock(member);var a=get(member,id);
            if(a.stale || a.revision!=revision || !profile(member).information().aiSummaryConsent()) throw AgentException.conflict("The assessment or explanation consent has changed. Refresh the page.");
            a.aiExplanation=explanation;a.aiModel=model;a.aiGeneratedAt=clock.instant();
            mapper.updateAssessment(a,json.writeValueAsString(a));return a;
        });
    }
    private Assessment withFreshness(Assessment a) {var p=mapper.profile(a.memberId);a.stale=p==null || p.profileVersion()!=a.profileVersion;return a;}
    private Assessment find(String id) {var row=mapper.assessment(id);if(row==null) throw notFound();return decode(row);}
    private Assessment decode(HealthMapper.AssessmentRow row) {
        var a=json.readValue(row.document(),Assessment.class);
        // Older complete snapshots predate observedDays; their covered days still give the correct denominator.
        if(a.observedDays==0 && a.intakeCount>0 && !a.incompleteRecords)a.observedDays=a.coveredDays;
        return a;
    }
    private void lock(String member) {if(mapper.lockMember(member)==null) throw notFound();}
    private AgentException notFound() {return new AgentException(HttpStatus.NOT_FOUND,"NOT_FOUND","Health assessment not found.");}
    private AgentException bad(String message) {return new AgentException(HttpStatus.BAD_REQUEST,"INVALID_HEALTH_INPUT",message);}
}
