package com.sylvia.bookhouse.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * books 表：官方书（owner_id NULL）+ 自创书（owner_id = 用户 id）统一入库。
 */
@Data
@TableName("books")
public class Book {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 业务索书号：官方书为 baiyan/liuru 等，自创书为 custom_<12hex> */
    private String bookId;
    /** 自创书作者；官方书为 null */
    private Long ownerId;
    private String title;
    private String emoji;
    private String gender;
    private String voice;
    /** JSON 数组字符串 */
    private String tags;
    private String intro;
    private String cardDesc;
    private String coverUrl;
    private String bgGradient;
    private String accentColor;
    /** JSON 对象字符串，键为五档情绪 */
    private String scenes;
    private String profile;
    /** JSON 数组字符串 */
    private String likes;
    /** JSON 数组字符串 */
    private String dislikes;
    /** JSON 数组字符串 */
    private String openingDialog;
    private String systemPrompt;
    private Integer status;
    /** v4.2 最近阅读时间（毫秒时间戳，可空；列表排序第一优先级） */
    private Long lastReadAt;
    /** v4.2 世界观（预留字段，Sylvia 世界观文档后续接入） */
    private String worldView;
    private Long createdAt;
    private Long updatedAt;
}
