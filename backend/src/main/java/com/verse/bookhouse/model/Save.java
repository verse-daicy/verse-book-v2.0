package com.sylvia.bookhouse.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("saves")
public class Save {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String data;
    private Long updatedAt;
}
