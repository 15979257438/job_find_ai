package com.getjobs.profile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.getjobs.profile.entity.UserProfileEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfileEntity> {
    @Select("SELECT * FROM user_profile WHERE is_default = 1 LIMIT 1")
    UserProfileEntity findDefault();

    @Select("SELECT * FROM user_profile ORDER BY id ASC LIMIT 1")
    UserProfileEntity findFirst();
}