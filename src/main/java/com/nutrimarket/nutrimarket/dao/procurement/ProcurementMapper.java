package com.nutrimarket.nutrimarket.dao.procurement;

import com.nutrimarket.nutrimarket.model.procurement.ProcurementModels.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ProcurementMapper {
    record Row(String id,String memberId,String document) {}
    @Select("SELECT basket_id AS id,member_id,document FROM shopping_basket WHERE basket_id=#{id}") Row basket(String id);
    @Select("SELECT basket_id AS id,member_id,document FROM shopping_basket WHERE result_id=#{id}") Row forResult(String id);
    @Insert("INSERT INTO shopping_basket(basket_id,member_id,result_id,state,document) VALUES(#{b.basketId},#{b.memberId},#{b.resultId},#{b.state},#{document})")
    int insert(@Param("b") Basket b,@Param("document") String document);
    @Update("UPDATE shopping_basket SET state=#{b.state},document=#{document} WHERE basket_id=#{b.basketId}")
    int save(@Param("b") Basket b,@Param("document") String document);
    @Select("SELECT basket_id AS id,member_id,document FROM shopping_basket WHERE member_id=#{member} ORDER BY created_at DESC,basket_id LIMIT #{size} OFFSET #{offset}")
    List<Row> baskets(@Param("member") String member,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT basket_id AS id,member_id,document FROM shopping_basket WHERE state='REVIEW_REQUIRED' ORDER BY created_at,basket_id LIMIT #{size} OFFSET #{offset}")
    List<Row> queue(@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT order_id AS id,member_id,document FROM procurement_order WHERE order_id=#{id}") Row order(String id);
    @Select("SELECT order_id AS id,member_id,document FROM procurement_order WHERE member_id=#{member} AND request_id=#{request}")
    Row request(@Param("member") String member,@Param("request") String request);
    // The request ID is bound as text: MySQL Connector/J cannot bind java.util.UUID.
    @Insert("INSERT INTO procurement_order(order_id,member_id,basket_id,request_id,document) VALUES(#{o.orderId},#{o.memberId},#{o.basketId},#{request},#{document})")
    int insertOrder(@Param("o") Order o,@Param("request") String request,@Param("document") String document);
    @Update("UPDATE procurement_order SET document=#{document} WHERE order_id=#{id}") int saveOrder(@Param("id") String id,@Param("document") String document);
    @Select("SELECT order_id AS id,member_id,document FROM procurement_order WHERE member_id=#{member} ORDER BY created_at DESC,order_id LIMIT #{size} OFFSET #{offset}")
    List<Row> orders(@Param("member") String member,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT order_id AS id,member_id,document FROM procurement_order ORDER BY order_id") List<Row> reportOrders();
    @Select("SELECT order_id AS id,member_id,document FROM procurement_order WHERE member_id=#{member} ORDER BY created_at,order_id") List<Row> memberOrders(String member);
    @Select("SELECT document FROM member_pantry WHERE member_id=#{member}") String pantry(String member);
    @Insert("INSERT INTO member_pantry(member_id,document) VALUES(#{member},#{document})") int insertPantry(@Param("member") String member,@Param("document") String document);
    @Update("UPDATE member_pantry SET document=#{document} WHERE member_id=#{member}") int savePantry(@Param("member") String member,@Param("document") String document);
    @Select("SELECT document FROM member_delivery_address WHERE member_id=#{member}") String address(String member);
    @Insert("INSERT INTO member_delivery_address(member_id,document) VALUES(#{member},#{document})") int insertAddress(@Param("member") String member,@Param("document") String document);
    @Update("UPDATE member_delivery_address SET document=#{document} WHERE member_id=#{member}") int saveAddress(@Param("member") String member,@Param("document") String document);
}
