package com.sylvia.bookhouse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sylvia.bookhouse.model.Save;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SaveMapper extends BaseMapper<Save> {

    @Select("SELECT id, user_id, data, updated_at FROM saves WHERE user_id = #{userId}")
    Save selectByUserId(@Param("userId") long userId);
}
