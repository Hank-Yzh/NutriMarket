package com.nutrimarket.nutrimarket.dao.health;

import com.nutrimarket.nutrimarket.model.health.HealthModels;
import java.time.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface HealthMapper {
    record ProfileRow(String memberId,long profileVersion,String document) {}
    record IntakeRow(String recordId,String memberId,long profileVersion,Instant mealTime,String document) {}
    record AssessmentRow(String assessmentId,String memberId,long profileVersion,LocalDate periodStart,String document) {}
    @Select("SELECT id FROM member_account WHERE id=#{member} FOR UPDATE") String lockMember(String member);
    @Select("SELECT member_id,profile_version,document FROM member_health_profile WHERE member_id=#{member} ORDER BY profile_version DESC LIMIT 1") ProfileRow profile(String member);
    @Insert("INSERT INTO member_health_profile(member_id,profile_version,document) VALUES(#{memberId},#{profileVersion},#{document})") int insertProfile(ProfileRow row);
    @Select("SELECT DISTINCT p.member_id FROM member_health_profile p JOIN member_account m ON m.id=p.member_id WHERE m.enabled=TRUE AND p.member_id>#{after} ORDER BY p.member_id LIMIT 100") List<String> members(String after);
    @Select("SELECT record_id FROM dietary_record WHERE record_id=#{id} AND status='VALIDATED' FOR UPDATE") String lockIntake(String id);
    @Select("SELECT record_id,member_id,profile_version,meal_time,document FROM health_intake WHERE record_id=#{id}") IntakeRow intake(String id);
    @Insert("INSERT INTO health_intake(record_id,member_id,profile_version,meal_time,document) VALUES(#{recordId},#{memberId},#{profileVersion},#{mealTime},#{document})") int insertIntake(IntakeRow row);
    @Select("SELECT o.record_id FROM dietary_outbox o JOIN dietary_record r ON r.record_id=o.record_id LEFT JOIN health_intake h ON h.record_id=o.record_id WHERE r.member_id=#{member} AND r.status='VALIDATED' AND h.record_id IS NULL ORDER BY o.record_id LIMIT 1000") List<String> missingIntakes(String member);
    @Select("SELECT record_id,member_id,profile_version,meal_time,document FROM health_intake WHERE member_id=#{member} AND meal_time>=#{start} AND meal_time<#{end} ORDER BY meal_time,record_id") List<IntakeRow> intakes(@Param("member") String member,@Param("start") Instant start,@Param("end") Instant end);
    @Select("SELECT assessment_id,member_id,profile_version,period_start,document FROM weekly_health_assessment WHERE assessment_id=#{id}") AssessmentRow assessment(String id);
    @Select("SELECT assessment_id,member_id,profile_version,period_start,document FROM weekly_health_assessment WHERE member_id=#{member} AND profile_version=#{version} AND period_start=#{start}") AssessmentRow period(@Param("member") String member,@Param("version") long version,@Param("start") LocalDate start);
    @Insert("INSERT INTO weekly_health_assessment(assessment_id,member_id,profile_version,period_start,clearance,document) VALUES(#{a.assessmentId},#{a.memberId},#{a.profileVersion},#{a.periodStart},#{a.planningClearance},#{document})") int insertAssessment(@Param("a") HealthModels.Assessment a,@Param("document") String document);
    @Select("SELECT a.assessment_id,a.member_id,a.profile_version,a.period_start,a.document FROM weekly_health_assessment a WHERE a.member_id=#{member} AND a.clearance='CLEARED' AND a.profile_version=(SELECT MAX(p.profile_version) FROM member_health_profile p WHERE p.member_id=a.member_id) ORDER BY a.period_start DESC LIMIT 1")
    AssessmentRow latestCleared(String member);
    @Select("SELECT DISTINCT a.member_id FROM weekly_health_assessment a JOIN member_account m ON m.id=a.member_id WHERE m.enabled=TRUE AND a.clearance='CLEARED' AND a.profile_version=(SELECT MAX(p.profile_version) FROM member_health_profile p WHERE p.member_id=a.member_id) ORDER BY a.member_id")
    List<String> clearedMembers();
    @Update("UPDATE weekly_health_assessment SET clearance=#{a.planningClearance},document=#{document} WHERE assessment_id=#{a.assessmentId}") int updateAssessment(@Param("a") HealthModels.Assessment a,@Param("document") String document);
    @Select("SELECT assessment_id,member_id,profile_version,period_start,document FROM weekly_health_assessment WHERE member_id=#{member} ORDER BY period_start DESC,profile_version DESC LIMIT #{size} OFFSET #{offset}") List<AssessmentRow> list(@Param("member") String member,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT a.assessment_id,a.member_id,a.profile_version,a.period_start,a.document FROM weekly_health_assessment a WHERE a.clearance='BLOCKED_FOR_EXPERT_REVIEW' AND a.profile_version=(SELECT MAX(p.profile_version) FROM member_health_profile p WHERE p.member_id=a.member_id) ORDER BY a.period_start,a.assessment_id LIMIT #{size} OFFSET #{offset}") List<AssessmentRow> reviewQueue(@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT COUNT(*) FROM weekly_health_assessment a WHERE a.clearance='BLOCKED_FOR_EXPERT_REVIEW' AND a.profile_version=(SELECT MAX(p.profile_version) FROM member_health_profile p WHERE p.member_id=a.member_id)") long reviewQueueCount();
    // Reviewed snapshots serialise a non-null expertDecision object; a LIKE filter keeps the query portable to the H2 test database.
    @Select("SELECT assessment_id,member_id,profile_version,period_start,document FROM weekly_health_assessment WHERE document LIKE '%\"expertDecision\":{%' ORDER BY period_start DESC,assessment_id LIMIT #{size} OFFSET #{offset}") List<AssessmentRow> reviewed(@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT COUNT(*) FROM weekly_health_assessment WHERE document LIKE '%\"expertDecision\":{%'") long reviewedCount();
}
