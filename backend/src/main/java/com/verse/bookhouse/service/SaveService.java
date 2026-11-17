package com.sylvia.bookhouse.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sylvia.bookhouse.mapper.SaveMapper;
import com.sylvia.bookhouse.model.Save;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * 云端存档（复刻 routes/api.js handleGetSaves/handlePutSaves）。
 * saves 表每用户一行，data 为 JSON 字符串：
 * { saves:{id:state}, customChars:[], settings:{}, bookmarks:[], visitorCard:{}, celebrated:{}, chatActivity:{} }
 */
@Service
public class SaveService {

    public static final long MAX_SAVE_JSON = 3_000_000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SaveMapper saveMapper;

    public SaveService(SaveMapper saveMapper) {
        this.saveMapper = saveMapper;
    }

    public Map<String, Object> getSaves(long userId) {
        Save row = saveMapper.selectByUserId(userId);
        Map<String, Object> res = new HashMap<>();
        res.put("data", row == null ? new HashMap<>() : parseData(row.getData()));
        res.put("updatedAt", row == null ? 0L : row.getUpdatedAt());
        return res;
    }

    /** @return code: 200/400/413 */
    public Map<String, Object> putSaves(long userId, Object data) {
        Map<String, Object> res = new HashMap<>();
        if (!(data instanceof Map)) { res.put("code", 400); res.put("error", "存档格式不对"); return res; }
        String json;
        try {
            json = MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            res.put("code", 400); res.put("error", "存档格式不对"); return res;
        }
        if (json.length() > MAX_SAVE_JSON) { res.put("code", 413); res.put("error", "书签太多了，这本册子装不下"); return res; }
        long now = System.currentTimeMillis();
        upsert(userId, json, now);
        res.put("code", 200);
        res.put("ok", true);
        res.put("updatedAt", now);
        return res;
    }

    @Transactional
    public void upsert(long userId, String json, long now) {
        Save row = saveMapper.selectByUserId(userId);
        if (row == null) {
            Save s = new Save();
            s.setUserId(userId);
            s.setData(json);
            s.setUpdatedAt(now);
            saveMapper.insert(s);
        } else {
            row.setData(json);
            row.setUpdatedAt(now);
            saveMapper.updateById(row);
        }
    }

    /** 注册后预置空存档（幂等） */
    public void ensureSaveRow(long userId) {
        Save row = saveMapper.selectByUserId(userId);
        if (row == null) {
            Save s = new Save();
            s.setUserId(userId);
            s.setData("{}");
            s.setUpdatedAt(System.currentTimeMillis());
            saveMapper.insert(s);
        }
    }

    public Map<String, Object> parseData(String data) {
        if (data == null || data.isEmpty()) return new HashMap<>();
        try {
            Map<String, Object> m = MAPPER.readValue(data, new TypeReference<Map<String, Object>>() {});
            return m == null ? new HashMap<>() : m;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /**
     * 删除自创书联动：从本人存档 JSON 中移除该书（saves[bookId]/customChars[id]/bookmarks/chatActivity[id]）。
     * 返回是否实际改动；写回失败不抛（由调用方决定）。
     */
    @Transactional
    public void removeBookFromSave(long userId, String bookId, String bookTitle) {
        Save row = saveMapper.selectByUserId(userId);
        if (row == null) return;
        Map<String, Object> data = parseData(row.getData());
        boolean changed = false;

        // saves[bookId]
        if (data.get("saves") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> saves = (Map<String, Object>) data.get("saves");
            if (saves.remove(bookId) != null) changed = true;
        }
        // customChars[id]
        if (data.get("customChars") instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<Object> chars = (java.util.List<Object>) data.get("customChars");
            java.util.Iterator<Object> it = chars.iterator();
            while (it.hasNext()) {
                Object c = it.next();
                if (c instanceof Map && bookId.equals(((Map<?, ?>) c).get("id"))) { it.remove(); changed = true; }
            }
        }
        // bookmarks 中该书
        if (data.get("bookmarks") instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<Object> bms = (java.util.List<Object>) data.get("bookmarks");
            java.util.Iterator<Object> it = bms.iterator();
            while (it.hasNext()) {
                Object b = it.next();
                if (b instanceof Map) {
                    Object bid = ((Map<?, ?>) b).get("bookId");
                    if (bid == null) bid = ((Map<?, ?>) b).get("book_id");
                    if (bookId.equals(String.valueOf(bid))) { it.remove(); changed = true; }
                }
            }
        }
        // chatActivity[id]
        if (data.get("chatActivity") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> act = (Map<String, Object>) data.get("chatActivity");
            if (act.remove(bookId) != null) changed = true;
        }

        if (changed) {
            try {
                upsert(userId, MAPPER.writeValueAsString(data), System.currentTimeMillis());
            } catch (Exception ignored) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
