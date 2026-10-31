package com.nutrimarket.nutrimarket.dao.dietary;

import com.nutrimarket.nutrimarket.dao.dietary.IntakeOutboxMapper;
import com.nutrimarket.nutrimarket.dao.dietary.IntakeRecordMapper.RecordRow;
import com.nutrimarket.nutrimarket.dao.dietary.IntakeRecordMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import static com.nutrimarket.nutrimarket.model.dietary.DietaryModels.*;

@Repository
public class IntakeRepository {
    private final IntakeRecordMapper records;
    private final IntakeOutboxMapper outbox;
    private final ObjectMapper json;
    public IntakeRepository(IntakeRecordMapper records, IntakeOutboxMapper outbox, ObjectMapper json) {
        this.records=records; this.outbox=outbox; this.json=json;
    }
    public record Stored(IntakeRecord record, String fingerprint, String fingerprintMemberId, byte[] photo, String mediaType) {}
    public Optional<Stored> find(UUID id) {
        return Optional.ofNullable(records.find(id.toString())).map(row->new Stored(
                decode(row),row.fingerprint(),row.fingerprintMemberId(),row.photo(),row.mediaType()));
    }
    public void insert(IntakeRecord r, String fingerprint, byte[] photo, String mediaType) {
        records.insert(new RecordRow(r.recordId.toString(),r.memberId,fingerprint,r.memberId,json.writeValueAsString(r),
                photo,mediaType,r.status.name(),r.createdAt,r.expiresAt));
    }
    public void save(IntakeRecord r) {
        records.updateDocument(new RecordRow(r.recordId.toString(),r.memberId,null,null,json.writeValueAsString(r),
                null,null,r.status.name(),r.createdAt,r.expiresAt));
        if(r.status==Status.VALIDATED || r.status==Status.ABANDONED || r.status==Status.EXPIRED || r.status==Status.REPLACEMENT_REQUIRED)
            records.clearPhoto(r.recordId.toString());
    }
    public List<IntakeRecord> findPage(String member, Status status, Instant now, int page, int size) {
        return records.findPage(member,status==null?null:status.name(),now,(long)(page-1)*size,size)
                .stream().map(this::decode).toList();
    }
    public long count(String member, Status status, Instant now) {
        return records.count(member,status==null?null:status.name(),now);
    }
    public void publish(ValidatedDietaryIntakeRecord payload) {
        outbox.insert(payload.recordId().toString(),json.writeValueAsString(payload));
    }
    public ValidatedDietaryIntakeRecord payload(UUID id) {
        return json.readValue(outbox.find(id.toString()).payload(),ValidatedDietaryIntakeRecord.class);
    }
    private IntakeRecord decode(RecordRow row) { return json.readValue(row.document(),IntakeRecord.class); }
}
