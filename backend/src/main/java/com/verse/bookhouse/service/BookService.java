package com.sylvia.bookhouse.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sylvia.bookhouse.exception.ApiException;
import com.sylvia.bookhouse.mapper.BookMapper;
import com.sylvia.bookhouse.mapper.ShareMapper;
import com.sylvia.bookhouse.model.Book;
import com.sylvia.bookhouse.model.Share;
import com.sylvia.bookhouse.util.AssetStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 书籍 CRUD（复刻 routes/api.js handleListBooks/handleGetBook/handleListMyBooks/
 * handleCreateBook/handleUpdateBook/handleDeleteBook）。
 *  - rowToBook / bookToRow 字段映射与 Node 完全一致
 *  - 更新为部分更新（v4.0.3 语义）：只 SET 显式传入字段，绝不覆盖为空
 *  - 删除为 v4.1 联动：作者本人 404 / 官方书 403，同一事务清理 saves JSON + shares + 落盘文件
 */
@Service
public class BookService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> JSON_COLS = Set.of("tags", "likes", "dislikes", "openingDialog", "scenes");
    private static final Set<String> VALID_GENDER = Set.of("male", "female", "neutral");

    private final BookMapper bookMapper;
    private final ShareMapper shareMapper;
    private final SaveService saveService;
    private final AssetStore assetStore;

    public BookService(BookMapper bookMapper, ShareMapper shareMapper, SaveService saveService, AssetStore assetStore) {
        this.bookMapper = bookMapper;
        this.shareMapper = shareMapper;
        this.saveService = saveService;
        this.assetStore = assetStore;
    }

    // ---------- 序列化（rowToBook / bookToRow） ----------

    static Object pick(String v, Object fb) {
        if (v == null || v.isEmpty()) return fb;
        try {
            return MAPPER.readValue(v, new TypeReference<Object>() {});
        } catch (Exception e) {
            return v;
        }
    }

    static String j(Object v) {
        if (v == null) return null;
        try {
            return MAPPER.writeValueAsString(v);
        } catch (Exception e) {
            return null;
        }
    }

    /** DB 行 → 前端角色对象（与 Node rowToBook 一致） */
    public Map<String, Object> toBookJson(Book row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getBookId());
        m.put("ownerId", row.getOwnerId());
        m.put("name", row.getTitle());
        m.put("emoji", row.getEmoji());
        m.put("gender", row.getGender());
        m.put("voice", row.getVoice());
        m.put("tags", pick(row.getTags(), new ArrayList<>()));
        m.put("intro", row.getIntro());
        m.put("cardDesc", row.getCardDesc() == null ? "" : row.getCardDesc());
        m.put("cover", row.getCoverUrl());
        m.put("bgGradient", row.getBgGradient());
        m.put("accentColor", row.getAccentColor());
        m.put("scenes", pick(row.getScenes(), new HashMap<>()));
        m.put("profile", row.getProfile() == null ? "" : row.getProfile());
        m.put("likes", pick(row.getLikes(), new ArrayList<>()));
        m.put("dislikes", pick(row.getDislikes(), new ArrayList<>()));
        m.put("openingDialog", pick(row.getOpeningDialog(), new ArrayList<>()));
        m.put("systemPrompt", row.getSystemPrompt() == null ? "" : row.getSystemPrompt());
        m.put("status", row.getStatus());
        m.put("lastReadAt", row.getLastReadAt());
        m.put("worldView", row.getWorldView() == null ? "" : row.getWorldView());
        m.put("createdAt", row.getCreatedAt());
        m.put("updatedAt", row.getUpdatedAt());
        return m;
    }

    /** 前端对象 → DB 行（与 Node bookToRow 一致） */
    public Book fromBookJson(Map<String, Object> src, Long ownerId) {
        Book b = new Book();
        b.setBookId(String.valueOf(src.get("id") == null ? "" : src.get("id")).trim());
        b.setOwnerId(ownerId);
        Object name = src.get("name");
        Object title = src.get("title");
        b.setTitle(String.valueOf(name == null ? (title == null ? "" : title) : name).trim());
        b.setEmoji(String.valueOf(src.get("emoji") == null ? "" : src.get("emoji")));
        Object gender = src.get("gender");
        b.setGender(gender != null && VALID_GENDER.contains(String.valueOf(gender)) ? String.valueOf(gender) : "neutral");
        b.setVoice(String.valueOf(src.get("voice") == null ? "" : src.get("voice")));
        b.setTags(j(src.get("tags") == null ? new ArrayList<>() : src.get("tags")));
        b.setIntro(String.valueOf(src.get("intro") == null ? "" : src.get("intro")));
        b.setCardDesc(String.valueOf(src.get("cardDesc") == null ? "" : src.get("cardDesc")));
        b.setCoverUrl(String.valueOf(src.get("cover") == null ? "" : src.get("cover")));
        b.setBgGradient(String.valueOf(src.get("bgGradient") == null ? "" : src.get("bgGradient")));
        b.setAccentColor(String.valueOf(src.get("accentColor") == null ? "" : src.get("accentColor")));
        b.setScenes(j(src.get("scenes") == null ? new HashMap<>() : src.get("scenes")));
        b.setProfile(String.valueOf(src.get("profile") == null ? "" : src.get("profile")));
        b.setLikes(j(src.get("likes") == null ? new ArrayList<>() : src.get("likes")));
        b.setDislikes(j(src.get("dislikes") == null ? new ArrayList<>() : src.get("dislikes")));
        b.setOpeningDialog(j(src.get("openingDialog") == null ? new ArrayList<>() : src.get("openingDialog")));
        b.setSystemPrompt(String.valueOf(src.get("systemPrompt") == null ? "" : src.get("systemPrompt")));
        Object status = src.get("status");
        b.setStatus(status instanceof Number && ((Number) status).intValue() == 0 ? 0 : 1);
        // v4.2 预留：世界观透传（自创书也可带世界观）
        if (src.get("worldView") != null) {
            b.setWorldView(String.valueOf(src.get("worldView")));
        }
        return b;
    }

    // ---------- 查询 ----------

    public List<Map<String, Object>> listOfficial() {
        // v4.2 官方书排序（主人设定）：最近读过 lastReadAt 倒序（NULL 沉底）→ 新建 createdAt 倒序 → id 倒序兜底
        // 本批 20 位男主 seed 时 createdAt=now 晚于白晏/邱立阳，默认置顶；新书插入 id 更大，同 created_at 时靠 id 兜底
        List<Book> rows = bookMapper.selectList(new LambdaQueryWrapper<Book>()
                .isNull(Book::getOwnerId).eq(Book::getStatus, 1)
                .orderByDesc(Book::getLastReadAt)
                .orderByDesc(Book::getCreatedAt)
                .orderByDesc(Book::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Book r : rows) out.add(toBookJson(r));
        return out;
    }

    public Map<String, Object> getOfficial(String bookId) {
        Book row = bookMapper.selectOne(new LambdaQueryWrapper<Book>()
                .eq(Book::getBookId, bookId).isNull(Book::getOwnerId).eq(Book::getStatus, 1));
        return row == null ? null : toBookJson(row);
    }

    public List<Map<String, Object>> listMine(long userId) {
        // v4.2 自创书排序同样：最近读过 lastReadAt 倒序 → 新建 updatedAt 倒序 → id 倒序兜底
        List<Book> rows = bookMapper.selectList(new LambdaQueryWrapper<Book>()
                .eq(Book::getOwnerId, userId)
                .orderByDesc(Book::getLastReadAt)
                .orderByDesc(Book::getUpdatedAt)
                .orderByDesc(Book::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Book r : rows) out.add(toBookJson(r));
        return out;
    }

    // ---------- 创建 ----------

    public Map<String, Object> create(Map<String, Object> src, long userId) {
        if (src == null || src.get("name") == null || String.valueOf(src.get("name")).trim().isEmpty()) {
            throw new ApiException(400, "这本书总得有个名字吧");
        }
        String bookId = "";
        for (int i = 0; i < 5; i++) {
            String candidate = "custom_" + hex(6);
            Long hit = bookMapper.selectCount(new LambdaQueryWrapper<Book>().eq(Book::getBookId, candidate));
            if (hit == null || hit == 0) { bookId = candidate; break; }
        }
        if (bookId.isEmpty()) throw new ApiException(500, "书架太挤了，等一下再试");

        Map<String, Object> book = new LinkedHashMap<>(src);
        book.put("id", bookId);
        book.put("ownerId", userId);
        assetStore.materializeBookAssets(bookId, book);

        Book row = fromBookJson(book, userId);
        long now = System.currentTimeMillis();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        bookMapper.insert(row);
        return toBookJson(row);
    }

    // ---------- 部分更新（v4.0.3 语义） ----------

    public Map<String, Object> update(String bookId, long userId, Map<String, Object> src) {
        if (src == null) src = new HashMap<>();
        Book exist = bookMapper.selectOne(new LambdaQueryWrapper<Book>()
                .eq(Book::getBookId, bookId).eq(Book::getOwnerId, userId));
        if (exist == null) throw new ApiException(404, "没找到这本书，或者它不是你的");
        if (src.get("name") != null && String.valueOf(src.get("name")).trim().isEmpty()) {
            throw new ApiException(400, "书名不能是空的");
        }

        // fieldMap: src key -> 列名（sceneArt 不在表中，传了忽略）
        Map<String, String> fieldMap = new LinkedHashMap<>();
        fieldMap.put("name", "title");
        fieldMap.put("title", "title");
        fieldMap.put("emoji", "emoji");
        fieldMap.put("gender", "gender");
        fieldMap.put("voice", "voice");
        fieldMap.put("tags", "tags");
        fieldMap.put("intro", "intro");
        fieldMap.put("cardDesc", "card_desc");
        fieldMap.put("cover", "cover_url");
        fieldMap.put("bgGradient", "bg_gradient");
        fieldMap.put("accentColor", "accent_color");
        fieldMap.put("scenes", "scenes");
        fieldMap.put("profile", "profile");
        fieldMap.put("likes", "likes");
        fieldMap.put("dislikes", "dislikes");
        fieldMap.put("openingDialog", "opening_dialog");
        fieldMap.put("systemPrompt", "system_prompt");
        fieldMap.put("worldView", "world_view");

        // 封面/插图 dataURL 先落盘再存 URL
        Map<String, Object> toBook = new LinkedHashMap<>();
        toBook.putAll(toBookJson(exist));
        toBook.putAll(src);
        toBook.put("id", bookId);
        toBook.put("ownerId", userId);
        assetStore.materializeBookAssets(bookId, toBook);

        UpdateWrapper<Book> uw = new UpdateWrapper<>();
        uw.eq("book_id", bookId).eq("owner_id", userId);
        boolean any = false;
        for (Map.Entry<String, String> e : fieldMap.entrySet()) {
            String k = e.getKey();
            if (!src.containsKey(k)) continue;
            String col = e.getValue();
            Object v;
            if (k.equals("cover") || k.equals("scenes")) {
                v = toBook.get(k) == null ? "" : toBook.get(k);
            } else {
                v = src.get(k);
            }
            if (JSON_COLS.contains(k)) {
                v = v == null ? null : j(v);
            } else if (k.equals("gender")) {
                if (!(v instanceof String) || !VALID_GENDER.contains(v)) continue; // 非法性别不覆盖
            } else {
                v = v == null ? "" : String.valueOf(v);
            }
            uw.set(col, v);
            any = true;
        }
        if (src.containsKey("status")) {
            Object st = src.get("status");
            uw.set("status", st != null && st instanceof Number && ((Number) st).intValue() == 0 ? 0 : 1);
            any = true;
        }
        if (!any) throw new ApiException(400, "这本书没有需要更新的内容");
        uw.set("updated_at", System.currentTimeMillis());
        bookMapper.update(null, uw);

        Book fresh = bookMapper.selectOne(new LambdaQueryWrapper<Book>()
                .eq(Book::getBookId, bookId).eq(Book::getOwnerId, userId));
        return toBookJson(fresh);
    }

    // ---------- 删除联动（v4.1） ----------

    @Transactional
    public void delete(String bookId, long userId) {
        Book exist = bookMapper.selectOne(new LambdaQueryWrapper<Book>().eq(Book::getBookId, bookId));
        if (exist == null) throw new ApiException(404, "没找到这本书，或者它不是你的");
        if (exist.getOwnerId() == null) throw new ApiException(403, "这本是书屋里珍藏的官方书，不能删哦");
        if (!exist.getOwnerId().equals(userId)) throw new ApiException(404, "没找到这本书，或者它不是你的"); // 不泄露他人书存在性

        // 同一事务：saves JSON + shares + books + 落盘文件
        saveService.removeBookFromSave(userId, bookId, exist.getTitle());
        shareMapper.delete(new LambdaQueryWrapper<Share>().eq(Share::getBookId, bookId));
        bookMapper.deleteById(exist.getId());
        assetStore.deleteBookFiles(bookId);
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
