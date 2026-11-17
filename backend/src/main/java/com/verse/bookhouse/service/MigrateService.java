package com.sylvia.bookhouse.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sylvia.bookhouse.exception.ApiException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.5 跨端迁移（复刻 handleMigrate + mergeCloudSave）。
 *  - preview：dry-run 不落库，返回合并报告
 *  - import：正式写入（>3MB 413）
 */
@Service
public class MigrateService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_SAVE_JSON = 3_000_000L;

    private final SaveService saveService;

    public MigrateService(SaveService saveService) {
        this.saveService = saveService;
    }

    public Map<String, Object> migrate(long userId, Map<String, Object> body, boolean dryRun) {
        Object dataObj = body == null ? null : body.get("data");
        if (!(dataObj instanceof Map)) {
            throw new ApiException(400, "存档格式不对，检查一下导出的文件吧");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> src = (Map<String, Object>) dataObj;
        boolean overwrite = Boolean.TRUE.equals(body.get("overwrite"));

        Map<String, Object> dst = saveService.getSaves(userId);
        @SuppressWarnings("unchecked")
        Map<String, Object> dstData = (Map<String, Object>) dst.get("data");

        // dry-run 在副本上试合并，不落库
        Map<String, Object> previewData = dryRun ? deepCopy(dstData) : dstData;
        Report report = mergeCloudSave(src, previewData, overwrite);

        if (dryRun) {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("ok", true);
            res.put("dryRun", true);
            res.put("report", report.toMap());
            res.put("cloudBooks", previewData.get("saves") instanceof Map ? ((Map<?, ?>) previewData.get("saves")).size() : 0);
            res.put("importBooks", src.get("saves") instanceof Map ? ((Map<?, ?>) src.get("saves")).size() : 0);
            return res;
        }

        String json;
        try {
            json = MAPPER.writeValueAsString(previewData);
        } catch (Exception e) {
            throw new ApiException(400, "存档格式不对，检查一下导出的文件吧");
        }
        if (json.length() > MAX_SAVE_JSON) {
            throw new ApiException(413, "合在一起太厚了，书屋的册子装不下");
        }
        long now = System.currentTimeMillis();
        saveService.upsert(userId, json, now);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("report", report.toMap());
        res.put("updatedAt", now);
        return res;
    }

    /** 合并策略与 Node mergeCloudSave 完全一致 */
    public static Report mergeCloudSave(Map<String, Object> src, Map<String, Object> dst, boolean overwrite) {
        Report r = new Report();

        // saves — 按书 id 合并，冲突默认保留云端
        if (src.get("saves") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sSaves = (Map<String, Object>) src.get("saves");
            if (!(dst.get("saves") instanceof Map)) dst.put("saves", new HashMap<>());
            @SuppressWarnings("unchecked")
            Map<String, Object> dSaves = (Map<String, Object>) dst.get("saves");
            for (Map.Entry<String, Object> e : sSaves.entrySet()) {
                String bookId = e.getKey();
                if (!dSaves.containsKey(bookId)) { dSaves.put(bookId, e.getValue()); r.added.add(bookId); }
                else if (overwrite) { dSaves.put(bookId, e.getValue()); r.overwritten.add(bookId); }
                else r.skipped.add(bookId);
            }
        }

        // customChars — 按 id 取并集
        if (src.get("customChars") instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> sChars = (List<Object>) src.get("customChars");
            if (!(dst.get("customChars") instanceof List)) dst.put("customChars", new ArrayList<>());
            @SuppressWarnings("unchecked")
            List<Object> dChars = (List<Object>) dst.get("customChars");
            Map<String, Object> byId = new LinkedHashMap<>();
            for (Object c : dChars) {
                if (c instanceof Map && ((Map<?, ?>) c).get("id") != null) {
                    byId.put(String.valueOf(((Map<?, ?>) c).get("id")), c);
                }
            }
            for (Object c : sChars) {
                if (!(c instanceof Map) || ((Map<?, ?>) c).get("id") == null) continue;
                String id = String.valueOf(((Map<?, ?>) c).get("id"));
                if (!byId.containsKey(id)) { byId.put(id, c); r.added.add(id + "（自创书）"); }
                else if (overwrite) { byId.put(id, c); r.overwritten.add(id + "（自创书）"); }
                else r.skipped.add(id + "（自创书）");
            }
            dst.put("customChars", new ArrayList<>(byId.values()));
        }

        // bookmarks — 按 id 取并集
        if (src.get("bookmarks") instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> sBm = (List<Object>) src.get("bookmarks");
            if (!(dst.get("bookmarks") instanceof List)) dst.put("bookmarks", new ArrayList<>());
            @SuppressWarnings("unchecked")
            List<Object> dBm = (List<Object>) dst.get("bookmarks");
            Map<String, Object> bmMap = new LinkedHashMap<>();
            for (Object b : dBm) {
                if (b instanceof Map && ((Map<?, ?>) b).get("id") != null) {
                    bmMap.put(String.valueOf(((Map<?, ?>) b).get("id")), b);
                }
            }
            int added = 0;
            for (Object b : sBm) {
                if (b instanceof Map && ((Map<?, ?>) b).get("id") != null) {
                    String id = String.valueOf(((Map<?, ?>) b).get("id"));
                    if (!bmMap.containsKey(id)) { bmMap.put(id, b); added++; }
                }
            }
            if (added > 0) {
                dst.put("bookmarks", new ArrayList<>(bmMap.values()));
                r.merged.add(added + " 条书签");
            }
        }

        // visitorCard — 取较新
        if (src.get("visitorCard") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sv = (Map<String, Object>) src.get("visitorCard");
            long svTs = num(sv.get("updatedAt"));
            long dvTs = 0;
            if (dst.get("visitorCard") instanceof Map) {
                dvTs = num(((Map<?, ?>) dst.get("visitorCard")).get("updatedAt"));
            }
            if (svTs > dvTs) { dst.put("visitorCard", sv); r.merged.add("访客卡"); }
        }

        // celebrated — 同年同 kind 取并集
        if (src.get("celebrated") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sCel = (Map<String, Object>) src.get("celebrated");
            if (!(dst.get("celebrated") instanceof Map)) dst.put("celebrated", new HashMap<>());
            @SuppressWarnings("unchecked")
            Map<String, Object> dCel = (Map<String, Object>) dst.get("celebrated");
            int added = 0;
            for (Map.Entry<String, Object> e : sCel.entrySet()) {
                if (!dCel.containsKey(e.getKey())) { dCel.put(e.getKey(), e.getValue()); added++; }
            }
            if (added > 0) r.merged.add(added + " 条庆祝记录");
        }

        // chatActivity — 同一本书取较新时间戳
        if (src.get("chatActivity") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sAct = (Map<String, Object>) src.get("chatActivity");
            if (!(dst.get("chatActivity") instanceof Map)) dst.put("chatActivity", new HashMap<>());
            @SuppressWarnings("unchecked")
            Map<String, Object> dAct = (Map<String, Object>) dst.get("chatActivity");
            int updated = 0;
            for (Map.Entry<String, Object> e : sAct.entrySet()) {
                Object ts = e.getValue();
                if (ts instanceof Number) {
                    long v = ((Number) ts).longValue();
                    Object d = dAct.get(e.getKey());
                    if (v > (d instanceof Number ? ((Number) d).longValue() : 0)) {
                        dAct.put(e.getKey(), v);
                        updated++;
                    }
                }
            }
            if (updated > 0) r.merged.add(updated + " 条活动记录");
        }
        return r;
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> src) {
        try {
            String json = MAPPER.writeValueAsString(src);
            Map<String, Object> m = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
            return m == null ? new HashMap<>() : m;
        } catch (Exception e) {
            return new HashMap<>(src);
        }
    }

    public static class Report {
        public final List<String> added = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
        public final List<String> overwritten = new ArrayList<>();
        public final List<String> merged = new ArrayList<>();

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("added", added);
            m.put("skipped", skipped);
            m.put("overwritten", overwritten);
            m.put("merged", merged);
            return m;
        }
    }
}
