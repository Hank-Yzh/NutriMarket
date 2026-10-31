package com.nutrimarket.nutrimarket.service.health;

import com.nutrimarket.nutrimarket.config.AgentConfiguration;
import com.nutrimarket.nutrimarket.dao.dietary.IntakeOutboxMapper;
import com.nutrimarket.nutrimarket.dao.health.HealthMapper;
import com.nutrimarket.nutrimarket.exception.AgentException;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.nutrimarket.nutrimarket.model.dietary.DietaryModels.*;

/** In-process trusted adapter: only accepts the immutable Agent 1 outbox, never client nutrient JSON. */
@Service
public class HealthInbox {
    private final HealthMapper mapper;
    private final IntakeOutboxMapper outbox;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final AgentConfiguration.Integrations integrations;
    public HealthInbox(HealthMapper mapper,IntakeOutboxMapper outbox,ObjectMapper json,TransactionTemplate tx,AgentConfiguration.Integrations integrations) {
        this.mapper=mapper;this.outbox=outbox;this.json=json;this.tx=tx;this.integrations=integrations;
    }
    public void accept(String id) {
        tx.executeWithoutResult(s->{
            if(mapper.lockIntake(id)==null) throw AgentException.conflict("Only validated meals can be used for health assessment.");
            var source=outbox.find(id);
            if(source==null) throw AgentException.conflict("Validated meal delivery data is missing.");
            var existing=mapper.intake(id);
            if(existing!=null) {
                if(!Objects.equals(existing.document(),source.payload())) throw AgentException.conflict("The content for this meal ID has changed.");
                if(integrations.healthAgent().endpoint().isBlank()) outbox.markDelivered(id);
                return;
            }
            var value=json.readValue(source.payload(),ValidatedDietaryIntakeRecord.class);
            if(value.schemaVersion()!=1 || !id.equals(value.recordId().toString()) || value.mealTime()==null)
                throw AgentException.conflict("Invalid meal data version or ID.");
            mapper.insertIntake(new HealthMapper.IntakeRow(id,value.memberId(),value.profileVersion(),value.mealTime(),source.payload()));
            if(integrations.healthAgent().endpoint().isBlank()) outbox.markDelivered(id);
        });
    }
    public void synchronize(String member) {
        while(true) {
            var missing=mapper.missingIntakes(member);
            for(var id:missing) accept(id);
            if(missing.size()<1000) return;
        }
    }
}
