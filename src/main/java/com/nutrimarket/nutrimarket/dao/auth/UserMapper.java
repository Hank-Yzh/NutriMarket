package com.nutrimarket.nutrimarket.dao.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import static com.nutrimarket.nutrimarket.model.auth.AuthModels.*;

@Mapper
public interface UserMapper {
    UserAccount findByUsername(@Param("username") String username);
    UserAccount findById(@Param("id") String id);
    int insert(UserAccount user);
}
