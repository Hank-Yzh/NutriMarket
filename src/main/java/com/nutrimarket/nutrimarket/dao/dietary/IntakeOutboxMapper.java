package com.nutrimarket.nutrimarket.dao.dietary;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IntakeOutboxMapper {
    record OutboxRow(String recordId, String payload, boolean delivered, int attempts, String lastError) {}
    int insert(@Param("id") String id, @Param("payload") String payload);
    OutboxRow find(@Param("id") String id);
    List<OutboxRow> findPending(@Param("maxAttempts") int maxAttempts);
    int incrementAttempts(@Param("id") String id);
    int markDelivered(@Param("id") String id);
    int recordFailure(@Param("id") String id, @Param("error") String error);
}
