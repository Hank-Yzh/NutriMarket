package com.nutrimarket.nutrimarket.service.planning;

import com.nutrimarket.nutrimarket.config.PlanningPolicy;
import com.nutrimarket.nutrimarket.dao.health.HealthMapper;
import com.nutrimarket.nutrimarket.dao.planning.PlanningMapper;
import com.nutrimarket.nutrimarket.exception.AgentException;
import com.nutrimarket.nutrimarket.integration.dietary.NutritionClient;
import com.nutrimarket.nutrimarket.integration.planning.PlanningClient;
import com.nutrimarket.nutrimarket.model.dietary.DietaryModels.FoodInput;
import com.nutrimarket.nutrimarket.model.health.HealthModels.*;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.*;
import com.nutrimarket.nutrimarket.service.health.HealthService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class PlanningService {
    private final ExecutorService nutritionWorkers=Executors.newFixedThreadPool(4);
    @jakarta.annotation.PreDestroy void stopNutritionWorkers(){nutritionWorkers.shutdownNow();}
    private final PlanningMapper mapper;private final HealthMapper healthMapper;private final HealthService health;
    private final PlanningEngine engine;private final IngredientCatalog catalog;private final NutritionClient nutrition;private final PlanningClient client;
    private final PlanningPolicy policy;private final ObjectMapper json;private final TransactionTemplate tx;private final Clock clock;
    public PlanningService(PlanningMapper mapper,HealthMapper healthMapper,HealthService health,PlanningEngine engine,IngredientCatalog catalog,NutritionClient nutrition,PlanningClient client,PlanningPolicy policy,ObjectMapper json,TransactionTemplate tx,Clock clock) {
        this.mapper=mapper;this.healthMapper=healthMapper;this.health=health;this.engine=engine;this.catalog=catalog;this.nutrition=nutrition;this.client=client;this.policy=policy;this.json=json;this.tx=tx;this.clock=clock;
    }
    public List<Ingredient> catalog(){return catalog.all();}
    /** Foods the member does not want in meal plans. They are a preference, not a health constraint, and never change the profile version. */
    public List<FoodDislike> dislikes(String member){return mapper.dislikes(member).stream().map(id->new FoodDislike(id,name(id))).toList();}
    public List<FoodDislike> dislike(String member,Collection<String> ingredientIds){
        for(String id:ingredientIds)if(catalog.get(id)==null)throw bad("Unknown food "+id+". Choose a food from the planning catalogue.");
        tx.executeWithoutResult(s->{lock(member);var saved=new HashSet<>(mapper.dislikes(member));for(String id:new TreeSet<>(ingredientIds))if(saved.add(id))mapper.insertDislike(member,id);});
        return dislikes(member);
    }
    public List<FoodDislike> undislike(String member,String ingredientId){tx.executeWithoutResult(s->{lock(member);mapper.deleteDislike(member,ingredientId);});return dislikes(member);}
    private String name(String id){var i=catalog.get(id);return i==null?id:i.name();}
    public Plan generate(String member,Generate request) {
        return generate(member,request,client,policy.model(),"Member consented to sharing the current assessment, planning constraints and preferences with Qwen.");
    }
    /**
     * Demo scenarios: plans the week from a fixed, pre-validated candidate instead of a Qwen proposal. The candidate still passes the
     * same validation, portion tuning and SubstitutionGroup packaging; later replans use Qwen as usual.
     */
    public Plan generateFromFixture(String member,Generate request,Candidate fixture,String name) {
        return generate(member,request,(input,goals,preferences,available,current,affected,feedback)->fixture,"demo fixture",
            "Demo scenario: the fixed meal plan \""+name+"\" replaced the Qwen proposal; no planning data was sent to Qwen.");
    }
    private Plan generate(String member,Generate request,PlanningClient source,String model,String detail) {
        consent(request.aiConsent());String token=UUID.randomUUID().toString();
        Plan p=tx.execute(s->{
            lock(member);var input=health.planningInput(member,request.assessmentId().toString());var assessment=health.get(member,input.assessmentId());
            LocalDate today=clock.instant().atZone(ZoneId.of(assessment.timezone)).toLocalDate();
            if(request.weekStart().isBefore(today) || request.weekStart().isAfter(today.plusWeeks(12)))throw bad("Choose a plan start date from today through the next 12 weeks in your health profile timezone.");
            var existing=mapper.existing(member,input.assessmentId(),request.weekStart());Plan previous=existing==null?null:refresh(decode(existing));
            // A plan made stale by a policy change can be regenerated for the same assessment and week.
            if(previous!=null && previous.assessmentRevision==assessment.revision && previous.state!=State.STALE)return previous;
            var plan=new Plan();plan.planId=UUID.randomUUID().toString();plan.memberId=member;plan.assessmentId=input.assessmentId();plan.assessmentRevision=assessment.revision;plan.profileVersion=input.profileVersion();plan.constraints=input.healthConstraintSet();
            plan.policyVersion=policy.policyVersion();plan.model=model;plan.weekStart=request.weekStart();plan.weekEnd=plan.weekStart.plusDays(6);plan.createdAt=clock.instant();plan.updatedAt=plan.createdAt;plan.state=State.GENERATING;plan.generationToken=token;
            if(previous!=null) {
                // A partial assessment can gain new meal evidence. Preserve history and exclusions, then validate a fresh plan version.
                plan.planId=previous.planId;plan.version=previous.version+1;plan.createdAt=previous.createdAt;
                plan.audit=new ArrayList<>(previous.audit);
                plan.replans=new ArrayList<>(previous.replans);plan.replanCount=previous.replanCount;plan.excludedIngredients=new ArrayList<>(previous.excludedIngredients);
            }
            // Disliked foods are left out of the ingredients offered to the planner and of the market substitutes.
            var disliked=mapper.dislikes(member).stream().filter(id->catalog.get(id)!=null).toList();
            Set<String> excluded=new TreeSet<>(plan.excludedIngredients);excluded.addAll(disliked);plan.excludedIngredients=new ArrayList<>(excluded);
            plan.priorities=input.incompleteRecords()
                    ?List.of("The assessment uses partial meal records. Plan from the supplied daily targets; missing meals do not establish low actual intake.")
                    :input.comparisons().stream().filter(c->!c.finding().equals("WITHIN_TARGET")).map(c->c.name()+": "+c.finding()+". Use the supplied target range without exceeding other upper limits.").toList();
            event(plan,previous==null?"GENERATION_REQUESTED":"ASSESSMENT_UPDATED",detail+(disliked.isEmpty()?"":" Foods you dislike are left out: "+String.join(", ",disliked.stream().map(this::name).toList())+"."));
            if(previous==null)mapper.insert(plan,json.writeValueAsString(plan));else save(plan);return plan;
        });
        return token.equals(p.generationToken)&&p.state==State.GENERATING?run(p,source):get(member,p.planId);
    }
    public Plan get(String member,String id) {
        return tx.execute(s->{lock(member);var p=refresh(owned(member,id));
            // A lease prevents a late response from overwriting a resumed generation.
            if(p.state==State.GENERATING && p.updatedAt.plus(Duration.ofMinutes(20)).isBefore(clock.instant())) {
                p.state=State.DEFERRED;p.generationToken=null;p.issues=List.of("Generation was interrupted. Retry generation.");event(p,"GENERATION_INTERRUPTED","Generation lease expired.");save(p);
            }
            var delivery=mapper.delivery(id,p.version);p.deliveryState=delivery==null?"NOT_PUBLISHED":delivery.state();return p;
        });
    }
    public PlanPage list(String member,int page,int size) {return new PlanPage(mapper.list(member,size,(long)(page-1)*size).stream().map(r->get(member,r.planId())).toList(),page,size);}
    public Plan retry(String member,String id,Version request) {
        var p=tx.execute(s->{lock(member);var plan=owned(member,id);active(plan);version(plan,request.expectedVersion());
            if(plan.state!=State.DEFERRED)throw conflict("Only a paused generation can be retried.");
            // Each member retry gets a fresh, bounded attempt budget.
            plan.attempts=0;plan.state=State.GENERATING;plan.generationToken=UUID.randomUUID().toString();event(plan,"RETRY_REQUESTED","Retrying with a fresh budget of "+policy.maxRevisions()+" attempts.");save(plan);return plan;
        });return run(p);
    }
    private Map<String,IngredientSource> sources(Plan p) {
        Map<String,Future<IngredientSource>> pending=new TreeMap<>();
        for(var ingredient:catalog.all())if(catalog.allowed(ingredient,p.constraints)&&!p.excludedIngredients.contains(ingredient.id())){
            pending.put(ingredient.id(),nutritionWorkers.submit(()->new IngredientSource(ingredient,nutrition.lookup(new FoodInput(ingredient.name(),ingredient.searchTerm(),BigDecimal.valueOf(100),BigDecimal.ONE,ingredient.fdcId())))));
        }
        try{
            Map<String,IngredientSource> result=new TreeMap<>();
            for(var entry:pending.entrySet())result.put(entry.getKey(),entry.getValue().get());
            return result;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw AgentException.provider("NUTRITION_INTERRUPTED","Nutrition loading was interrupted. Retry generation.");}
        catch(ExecutionException e){if(e.getCause() instanceof RuntimeException r)throw r;throw AgentException.provider("NUTRITION_FAILURE","Nutrition evidence could not be loaded.");}
        finally{pending.values().forEach(task->{if(!task.isDone())task.cancel(true);});}
    }
    private Plan run(Plan initial){return run(initial,client);}
    private Plan run(Plan initial,PlanningClient source) {
        String token=initial.generationToken;Map<String,IngredientSource> available=null;
        while(true) {
            Plan p=tx.execute(s->{lock(initial.memberId);var current=refresh(owned(initial.memberId,initial.planId));
                if(current.state!=State.GENERATING||!Objects.equals(token,current.generationToken))return current;
                current.attempts++;event(current,"GENERATION_ATTEMPT","Attempt "+current.attempts+" of "+policy.maxRevisions()+".");save(current);return current;
            });
            if(p.state!=State.GENERATING||!Objects.equals(token,p.generationToken))return p;
            try {
                if(available==null)available=sources(p);
                if(available.isEmpty())throw AgentException.provider("NO_ELIGIBLE_INGREDIENTS","No ingredients satisfy the current constraints.");
                var input=health.planningInput(p.memberId,p.assessmentId);var profile=health.profile(p.memberId);
                var info=profile.information();var styles=info.mealStyles()==null||info.mealStyles().isEmpty()?"":"Preferred meal styles: "+String.join(", ",info.mealStyles())+". ";
                var repairLines=engine.repairLines(p);
                String previousCandidate=json.writeValueAsString(p.candidate);var previousIssues=List.copyOf(p.issues);
                var proposed=source.propose(input,info.goals(),styles+info.preferences(),available,p.candidate,repairLines,p.issues);
                // Retain nutrition evidence for unchanged meals during a partial replan.
                var merged=engine.merge(p.candidate,proposed,repairLines);
                if(merged==null)throw AgentException.provider("INVALID_PLAN_RESPONSE","The model did not return exactly one replacement for each requested meal slot: "+String.join(", ",repairLines)+". Unaffected meals were preserved.");
                p.sources.putAll(available);p.candidate=merged;engine.validate(p);
                // Rescale portions deterministically before spending another model attempt on arithmetic misses.
                if(!p.issues.isEmpty()){var tuned=engine.tune(p);if(tuned!=null){p.candidate=tuned;engine.validate(p);event(p,"PORTIONS_ADJUSTED","Proposed portions were rescaled toward daily target midpoints; ingredients and unaffected meals were not changed.");}}
                boolean repeated=!p.issues.isEmpty()&&previousIssues.equals(p.issues)&&previousCandidate.equals(json.writeValueAsString(p.candidate));
                if(repeated){var issues=new ArrayList<>(p.issues);issues.add("The model repeated the same invalid meals without fixing the feedback. Generation stopped to avoid repeating identical requests.");p.issues=List.copyOf(issues);}
                p.state=p.issues.isEmpty()?State.DRAFT:repeated||p.attempts>=policy.maxRevisions()?State.DEFERRED:State.GENERATING;
                event(p,"VALIDATION_RESULT",p.issues.isEmpty()?"Numeric and recognised ingredient constraints passed.":String.join(" ",p.issues));
            } catch(AgentException e) {
                p.state=State.DEFERRED;p.issues=List.of(e.getMessage());event(p,"PROVIDER_FAILURE",e.code());
            } catch(RuntimeException e) {
                p.state=State.DEFERRED;p.issues=List.of("Generation failed. Retry after checking the provider configuration.");event(p,"GENERATION_FAILURE",e.getClass().getSimpleName());
            }
            Plan result=tx.execute(s->{lock(p.memberId);var latest=refresh(owned(p.memberId,p.planId));
                if(latest.state!=State.GENERATING||!Objects.equals(token,latest.generationToken))return latest;
                if(p.state!=State.GENERATING)p.generationToken=null;save(p);return p;
            });
            if(result.state!=State.GENERATING)return get(result.memberId,result.planId);
        }
    }
    public Plan publish(String member,String id,Publish request) {
        if(!request.confirmSources())throw bad("Confirm the ingredient forms, USDA sources and recipe limitations before publishing.");
        return publish(member,id,request.expectedVersion(),"PUBLISHED","Member confirmed ingredient forms and nutrition sources; recipe checks do not certify retail SKU safety.");
    }
    /** Used by the meal pipeline under the member's standing planning consent. */
    public Plan publishAutomatically(String member,String id,int expectedVersion) {
        return publish(member,id,expectedVersion,"AUTO_PUBLISHED","Published automatically under the member's standing planning consent; recipe checks do not certify retail SKU safety.");
    }
    private Plan publish(String member,String id,int expectedVersion,String action,String detail) {
        return tx.execute(s->{lock(member);var p=owned(member,id);active(p);version(p,expectedVersion);
            if(p.state==State.PUBLISHED)return p;
            if(p.state!=State.DRAFT)throw conflict("Only a validated draft can be published.");
            engine.validate(p);if(!p.issues.isEmpty())throw conflict("Unresolved nutrition or recipe constraints prevent publication.");
            p.publishedAt=clock.instant();p.state=State.PUBLISHED;event(p,action,detail);
            mapper.publish(id,p.version,json.writeValueAsString(engine.pack(p)));save(p);return p;
        });
    }
    public PlanningPackage packageFor(String member,String id) {
        return tx.execute(s->{lock(member);var p=owned(member,id);active(p);
            if(p.state!=State.PUBLISHED)throw conflict("Publish the current plan before requesting a planning package.");
            var row=mapper.delivery(id,p.version);if(row==null||row.state().equals("CANCELLED"))throw conflict("The planning package is no longer active.");return engine.forPurchasing(json.readValue(row.payload(),PlanningPackage.class));
        });
    }
    String packageJsonFor(String member,String id){return json.writeValueAsString(packageFor(member,id));}
    /** How many more replans this plan accepts before Agent 3 refuses with REPLAN_LIMIT_REACHED. */
    public int replansLeft(String member,String id){return Math.max(0,policy.maxReplans()-get(member,id).replanCount);}
    public Plan replan(String member,String id,Replan request) {
        consent(request.aiConsent());String token=UUID.randomUUID().toString();
        var p=tx.execute(s->{lock(member);var plan=owned(member,id);active(plan);
            for(var old:plan.replans)if(old.requestId().equals(request.requestId())) {
                if(old.fromVersion()!=request.expectedVersion()||old.reason()!=request.reason()||!old.evidence().equals(request.evidence())||!old.menuLineIds().equals(request.menuLineIds())||!old.unavailableIngredientIds().equals(request.unavailableIngredientIds()))throw conflict("This replan request ID was used for different content.");return plan;
            }
            version(plan,request.expectedVersion());if(plan.state!=State.PUBLISHED)throw conflict("Only the currently published plan can be replanned.");
            // The published plan stays active; Agent 5 escalates the shopping basket to expert review instead.
            if(plan.replanCount>=policy.maxReplans())throw new AgentException(HttpStatus.CONFLICT,"REPLAN_LIMIT_REACHED","The automatic replan limit for this plan has been reached.");
            if(request.planId()!=null&&!request.planId().toString().equals(id))throw bad("This replan request is for a different plan.");
            int attempt=plan.replans.size()+1;if(request.attempt()!=null&&request.attempt()!=attempt)throw conflict("The replan attempt count does not match this plan's replan history.");
            var lines=plan.meals.stream().map(Meal::menuLineId).toList();
            if(!lines.containsAll(request.menuLineIds())||new HashSet<>(request.menuLineIds()).size()!=request.menuLineIds().size()||request.unavailableIngredientIds().stream().anyMatch(i->catalog.get(i)==null))throw bad("Invalid affected meal lines or ingredient IDs.");
            for(String ingredient:request.unavailableIngredientIds())if(plan.meals.stream().filter(m->request.menuLineIds().contains(m.menuLineId())).noneMatch(m->m.ingredients().stream().anyMatch(i->ingredient.equals(i.ingredientId()))))throw bad("Each unavailable ingredient must occur in an affected meal.");
            plan.replans.add(new ReplanAudit(request.requestId(),plan.version,request.reason(),request.evidence(),List.copyOf(request.menuLineIds()),List.copyOf(request.unavailableIngredientIds()),clock.instant(),attempt));
            mapper.cancel(id);plan.version++;plan.attempts=0;plan.publishedAt=null;plan.affectedLines=List.copyOf(request.menuLineIds());
            Set<String> excluded=new TreeSet<>(plan.excludedIngredients);excluded.addAll(request.unavailableIngredientIds());plan.excludedIngredients=new ArrayList<>(excluded);
            plan.replanCount++;plan.model=policy.model();plan.state=State.GENERATING;plan.generationToken=token;plan.issues=List.of(request.reason()==ReplanReason.PREFERENCE?"The member dislikes "+String.join(", ",request.unavailableIngredientIds().stream().map(this::name).toList())+". Replace only the affected lines without it; preserve every other meal."
                :"Replace only the affected lines without the unavailable ingredients; preserve every other meal.");event(plan,"REPLAN_REQUESTED",request.reason().name());
            save(plan);return plan;
        });return token.equals(p.generationToken)?run(p):get(member,id);
    }
    private Plan refresh(Plan p) {
        boolean invalid=!p.policyVersion.equals(policy.policyVersion());
        try {var a=health.get(p.memberId,p.assessmentId);invalid|=a.stale||a.profileVersion!=p.profileVersion||a.revision!=p.assessmentRevision||a.planningClearance!=Clearance.CLEARED||a.status!=AssessmentStatus.COMPLETED||p.weekEnd.isBefore(clock.instant().atZone(ZoneId.of(a.timezone)).toLocalDate());
            invalid|=a.riskStatus==Risk.POTENTIAL_HIGH_RISK && (a.expertDecision==null || !Set.of(Decision.CLEARED_FOR_PLANNING,Decision.MODIFIED_AND_CLEARED).contains(a.expertDecision.decision()));
        }catch(AgentException e){invalid=true;}
        if(invalid&&p.state!=State.STALE){p.stale=true;p.state=State.STALE;p.generationToken=null;mapper.cancel(p.planId);event(p,"INVALIDATED","Profile, assessment clearance, policy or planning period is no longer current.");save(p);}
        else if(p.state==State.REVIEW_REQUIRED){engine.validate(p);p.state=p.issues.isEmpty()?State.DRAFT:State.DEFERRED;p.generationToken=null;
            event(p,"EXPERT_REVIEW_REMOVED",p.issues.isEmpty()?"Meal plans no longer need expert review; this validated plan is ready to publish.":"Meal plans no longer need expert review; retry generation to resolve the remaining checks.");save(p);}
        return p;
    }
    private void active(Plan p){if(refresh(p).state==State.STALE)throw conflict("This plan is stale. Obtain a current cleared assessment and generate a new plan.");}
    private void version(Plan p,int version){if(p.version!=version)throw conflict("This plan has changed. Refresh before continuing.");}
    private void consent(boolean consent){if(!consent)throw bad("Consent is required to send planning constraints and preferences to Qwen.");}
    private Plan owned(String member,String id){var p=read(id);if(!p.memberId.equals(member))throw missing();return p;}
    private Plan read(String id){var r=mapper.find(id);if(r==null)throw missing();return decode(r);}
    private Plan decode(PlanningMapper.Row row){return engine.purchaseIngredients(json.readValue(row.document(),Plan.class));}
    private void lock(String member){if(healthMapper.lockMember(member)==null)throw missing();}
    private void event(Plan p,String action,String detail){p.updatedAt=clock.instant();p.audit.add(new Event(p.updatedAt,p.version,action,detail));}
    private void save(Plan p){mapper.update(p,json.writeValueAsString(p));}
    private AgentException missing(){return new AgentException(HttpStatus.NOT_FOUND,"NOT_FOUND","Meal plan not found.");}
    private AgentException conflict(String message){return AgentException.conflict(message);}
    private AgentException bad(String message){return new AgentException(HttpStatus.BAD_REQUEST,"INVALID_PLANNING_INPUT",message);}
}
