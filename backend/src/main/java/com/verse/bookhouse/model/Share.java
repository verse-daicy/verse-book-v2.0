package com.sylvia.bookhouse.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * shares 表：荐书笺短链索引（code -> book_id + payload JSON）。
 */
@Data
@TableName("shares")
public class Share {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String bookId;
    /** JSON 字符串（书对象 payload） */
    private String payload;
    private Long createdAt;
}
