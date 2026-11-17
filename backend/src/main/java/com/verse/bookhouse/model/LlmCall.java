package com.sylvia.bookhouse.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * llm_calls 表：聊天调用留痕。
 */
@Data
@TableName("llm_calls")
public class LlmCall {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String model;
    private Integer tokens; 
    private Long createdAt;
}