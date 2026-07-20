package com.example.interviewreader.persistence.mapper;

import com.example.interviewreader.persistence.entity.AppUserEntity;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AppUserMapper extends BaseMapper<AppUserEntity> {
    @Select("SELECT id FROM app_user WHERE id = #{userId} FOR UPDATE")
    String selectIdForUpdate(@Param("userId") String userId);
}
