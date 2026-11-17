package com.sylvia.bookhouse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sylvia.bookhouse.model.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
