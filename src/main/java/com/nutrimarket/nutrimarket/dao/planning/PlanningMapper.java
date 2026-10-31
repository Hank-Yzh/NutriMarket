package com.nutrimarket.nutrimarket.dao.planning;

import org.apache.ibatis.annotations.*;
import java.time.*;
import java.util.List;
import com.nutrimarket.nutrimarket.model.planning.PlanningModels.Plan;

@Mapper
public interface PlanningMapper {
    record Row(String planId,String memberId,String document) {}
    record Outbox(String planId,int planVersion,String payload,String state,int attempts,String lastError) {}
    @Select("SELECT plan_id,member_id,document FROM nutrition_plan WHERE plan_id=#{id}") Row find(String id);
    @Select("SELECT plan_id,member_id,document FROM nutrition_plan WHERE member_id=#{member} AND assessment_id=#{assessment} AND week_start=#{week}")
    Row existing(@Param("member") String member,@Param("assessment") String assessment,@Param("week") LocalDate week);
    @Select("SELECT plan_id,member_id,document FROM nutrition_plan WHERE member_id=#{member} AND assessment_id=#{assessment} ORDER BY week_start DESC LIMIT 1")
    Row latestForAssessment(@Param("member") String member,@Param("assessment") String assessment);
    @Insert("INSERT INTO nutrition_plan(plan_id,member_id,assessment_id,profile_version,week_start,state,document) VALUES(#{p.planId},#{p.memberId},#{p.assessmentId},#{p.profileVersion},#{p.weekStart},#{p.state},#{document})")
    int insert(@Param("p") Plan plan,@Param("document") String document);
    @Update("UPDATE nutrition_plan SET state=#{p.state},document=#{document} WHERE plan_id=#{p.planId}")
    int update(@Param("p") Plan plan,@Param("document") String document);
    @Select("SELECT plan_id,member_id,document FROM nutrition_plan WHERE member_id=#{member} ORDER BY week_start DESC,plan_id LIMIT #{size} OFFSET #{offset}")
    List<Row> list(@Param("member") String member,@Param("size") int size,@Param("offset") long offset);
    @Insert("INSERT INTO planning_outbox(plan_id,plan_version,payload,state) VALUES(#{id},#{version},#{payload},'QUEUED')")
    int publish(@Param("id") String id,@Param("version") int version,@Param("payload") String payload);
    @Select("SELECT plan_id,plan_version,payload,state,attempts,last_error FROM planning_outbox WHERE plan_id=#{id} AND plan_version=#{version}")
    Outbox delivery(@Param("id") String id,@Param("version") int version);
    @Update("UPDATE planning_outbox SET state='CANCELLED' WHERE plan_id=#{id} AND state<>'CANCELLED'") int cancel(String id);
    @Update("UPDATE planning_outbox SET state='CANCELLED' WHERE plan_id=#{id} AND plan_version=#{version}")
    int cancelVersion(@Param("id") String id,@Param("version") int version);
    @Select("SELECT plan_id,plan_version,payload,state,attempts,last_error FROM planning_outbox WHERE state='QUEUED' AND attempts<#{max} ORDER BY created_at,plan_id LIMIT 20") List<Outbox> pending(int max);
    @Update("UPDATE planning_outbox SET attempts=attempts+1,state=#{state},last_error=#{error} WHERE plan_id=#{id} AND plan_version=#{version} AND state='QUEUED'")
    int result(@Param("id") String id,@Param("version") int version,@Param("state") String state,@Param("error") String error);
    @Select("SELECT ingredient_id FROM member_food_dislike WHERE member_id=#{member} ORDER BY created_at,ingredient_id") List<String> dislikes(String member);
    @Insert("INSERT INTO member_food_dislike(member_id,ingredient_id) VALUES(#{member},#{ingredient})")
    int insertDislike(@Param("member") String member,@Param("ingredient") String ingredient);
    @Delete("DELETE FROM member_food_dislike WHERE member_id=#{member} AND ingredient_id=#{ingredient}")
    int deleteDislike(@Param("member") String member,@Param("ingredient") String ingredient);
}
