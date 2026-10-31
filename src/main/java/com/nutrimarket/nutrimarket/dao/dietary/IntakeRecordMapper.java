package com.nutrimarket.nutrimarket.dao.dietary;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IntakeRecordMapper {
    record RecordRow(String recordId, String memberId, String fingerprint, String fingerprintMemberId, String document, byte[] photo,
                     String mediaType, String status, Instant createdAt, Instant expiresAt) {}
    RecordRow find(@Param("id") String id);
    int insert(RecordRow row);
    int updateDocument(RecordRow row);
    int clearPhoto(@Param("id") String id);
    List<RecordRow> findPage(@Param("memberId") String memberId, @Param("status") String status,
                            @Param("now") Instant now, @Param("offset") long offset, @Param("size") int size);
    long count(@Param("memberId") String memberId, @Param("status") String status, @Param("now") Instant now);
}
