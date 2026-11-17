package com.sylvia.bookhouse.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Data
@TableName("users")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    
    // 【修改点】：加上这个注解，该字段永远不会被转成 JSON 传给前端
    @JsonIgnore 
    private String passwordHash;
    
    private Long createdAt;
}