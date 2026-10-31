package com.nutrimarket.nutrimarket.dao.market;

import org.apache.ibatis.annotations.*;
import java.util.List;
import com.nutrimarket.nutrimarket.model.market.MarketModels.MarketResult;

@Mapper
public interface MarketMapper {
    record Row(String resultId,String memberId,String document) {}
    record ProductRow(String productId,int version,String document) {}
    record Outbox(String resultId,int revision,String payload,String state,int attempts) {}
    @Select("SELECT product_id,version,document FROM market_product WHERE product_id=#{id}") ProductRow product(String id);
    @Select("SELECT product_id,version,document FROM market_product ORDER BY updated_at DESC,product_id LIMIT #{size} OFFSET #{offset}")
    List<ProductRow> products(@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT product_id,version,document FROM market_product WHERE ingredient_id=#{ingredient} AND enabled=TRUE ORDER BY product_id LIMIT #{limit}")
    List<ProductRow> candidates(@Param("ingredient") String ingredient,@Param("limit") int limit);
    @Insert("INSERT INTO market_product(product_id,version,ingredient_id,enabled,document) VALUES(#{id},1,#{ingredient},#{enabled},#{document})")
    int insertProduct(@Param("id") String id,@Param("ingredient") String ingredient,@Param("enabled") boolean enabled,@Param("document") String document);
    @Update("UPDATE market_product SET version=version+1,ingredient_id=#{ingredient},enabled=#{enabled},document=#{document},updated_at=CURRENT_TIMESTAMP(3) WHERE product_id=#{id} AND version=#{expected}")
    int updateProduct(@Param("id") String id,@Param("expected") int expected,@Param("ingredient") String ingredient,@Param("enabled") boolean enabled,@Param("document") String document);
    @Insert("INSERT INTO market_product_history(product_id,version,document) VALUES(#{id},#{version},#{document})")
    int productHistory(@Param("id") String id,@Param("version") int version,@Param("document") String document);
    @Select("SELECT result_id,member_id,document FROM market_result WHERE result_id=#{id}") Row find(String id);
    @Select("SELECT result_id,member_id,document FROM market_result WHERE plan_id=#{id} AND plan_version=#{version}")
    Row forPlan(@Param("id") String id,@Param("version") int version);
    @Select("SELECT result_id,member_id,document FROM market_result WHERE member_id=#{member} ORDER BY created_at DESC,result_id LIMIT #{size} OFFSET #{offset}")
    List<Row> list(@Param("member") String member,@Param("size") int size,@Param("offset") long offset);
    @Insert("INSERT INTO market_result(result_id,member_id,plan_id,plan_version,document) VALUES(#{r.resultId},#{r.memberId},#{r.planId},#{r.planVersion},#{document})")
    int insert(@Param("r") MarketResult r,@Param("document") String document);
    @Update("UPDATE market_result SET document=#{document} WHERE result_id=#{r.resultId}")
    int update(@Param("r") MarketResult r,@Param("document") String document);
    @Insert("INSERT INTO market_outbox(result_id,revision,payload,state) VALUES(#{id},#{revision},#{payload},'QUEUED')")
    int publish(@Param("id") String id,@Param("revision") int revision,@Param("payload") String payload);
    @Select("SELECT result_id,revision,payload,state,attempts FROM market_outbox WHERE result_id=#{id} AND revision=#{revision}")
    Outbox delivery(@Param("id") String id,@Param("revision") int revision);
    @Update("UPDATE market_outbox SET state='CANCELLED' WHERE result_id=#{id}") int cancel(String id);
    @Update("UPDATE market_outbox SET state='CANCELLED' WHERE result_id=#{id} AND revision=#{revision}") int cancelVersion(@Param("id") String id,@Param("revision") int revision);
    @Select("SELECT result_id,revision,payload,state,attempts FROM market_outbox WHERE state='QUEUED' AND attempts<#{max} ORDER BY created_at,result_id LIMIT 20") List<Outbox> pending(int max);
    @Update("UPDATE market_outbox SET attempts=attempts+1,state=#{state} WHERE result_id=#{id} AND revision=#{revision} AND state='QUEUED'")
    int delivered(@Param("id") String id,@Param("revision") int revision,@Param("state") String state);
}
