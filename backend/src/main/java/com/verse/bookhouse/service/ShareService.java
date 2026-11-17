package com.sylvia.bookhouse.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sylvia.bookhouse.exception.ApiException;
import com.sylvia.bookhouse.mapper.BookMapper;
import com.sylvia.bookhouse.mapper.ShareMapper;
import com.sylvia.bookhouse.model.Book;
import com.sylvia.bookhouse.model.Share;
import com.sylvia.bookhouse.util.AssetStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 荐书笺（复刻 handlePutShare/handleGetShare）：
 *  - POST /api/share 把自创书整本 JSON 寄存（upsert，关联 books 表）
 *  - GET /api/share/:code 凭索书号取书：先查 shares，查不到直接回落 books 表
 *    （官方书 baiyan / 自创书 custom_xxx 都支持，v4.0.3 寻书修复）
 *  - 书封 dataURL 落盘到 public/shares/<code>.jpg 换公网 url
 */
@Service
public class ShareService {

    private static final Pattern SHARE_CODE_RE = Pattern.compile("^[\\u4e00-\\u9fa5a-zA-Z0-9_-]{1,64}$");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_PAYLOAD = 2_000_000L;

    private final ShareMapper shareMapper;
    private final BookMapper bookMapper;
    private final BookService bookService;
    private final AssetStore assetStore;

    public ShareService(ShareMapper shareMapper, BookMapper bookMapper, BookService bookService, AssetStore assetStore) {
        this.shareMapper = shareMapper;
        this.bookMapper = bookMapper;
        this.bookService = bookService;
        this.assetStore = assetStore;
    }

    public Map<String, Object> putShare(String codeRaw, String payload) {
        String code = codeRaw == null ? "" : codeRaw.trim();
        if (!SHARE_CODE_RE.matcher(code).matches()) {
            throw new ApiException(400, "索书号的写法不太对，书屋登记不上");
        }
        if (payload == null || payload.isEmpty() || payload.length() > MAX_PAYLOAD) {
            throw new ApiException(400, "这本书的书页太厚了，书屋里暂时登记不下");
        }
        try {
            MAPPER.readValue(payload, new TypeReference<Object>() {});
        } catch (Exception e) {
            throw new ApiException(400, "书页散了，没能装订成册");
        }
        // bookId 可选，关联 books 表（payload 为兼容冗余）
        String bookId = "";
        try {
            Map<String, Object> parsed = MAPPER.readValue(payload, new TypeReference<Map<String, Object>>() {});
            Object id = parsed == null ? null : parsed.get("id");
            if (id != null && SHARE_CODE_RE.matcher(String.valueOf(id)).matches()) bookId = String.valueOf(id);
        } catch (Exception ignored) {
        }
        long now = System.currentTimeMillis();
        Share exist = shareMapper.selectOne(new LambdaQueryWrapper<Share>().eq(Share::getCode, code));
        if (exist == null) {
            Share s = new Share();
            s.setCode(code);
            s.setBookId(bookId);
            s.setPayload(payload);
            s.setCreatedAt(now);
            shareMapper.insert(s);
        } else {
            exist.setBookId(bookId);
            exist.setPayload(payload);
            exist.setCreatedAt(now);
            shareMapper.updateById(exist);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("code", code);
        return res;
    }

    public Map<String, Object> getShare(String rawCode, HttpServletRequest req) {
        String code;
        try {
            code = URLDecoder.decode(rawCode, StandardCharsets.UTF_8);
        } catch (Exception e) {
            code = rawCode;
        }
        if (!SHARE_CODE_RE.matcher(code).matches()) {
            throw new ApiException(400, "这个索书号的写法不太对");
        }
        String origin = requestOrigin(req);

        // 1) 先查 shares
        Share row = null;
        try {
            row = shareMapper.selectOne(new LambdaQueryWrapper<Share>().eq(Share::getCode, code));
        } catch (Exception ignored) {
        }
        // 2) 无 shares → 回落 books 表（官方书/自创书 book_id 直查）
        if (row == null) {
            Book bk = bookMapper.selectOne(new LambdaQueryWrapper<Book>().eq(Book::getBookId, code));
            if (bk != null) {
                Map<String, Object> book = bookService.toBookJson(bk);
                materializeShareCoverIfNeeded(code, book, origin);
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("payload", book);
                return res;
            }
        }
        // 3) shares 关联 books 表
        if (row != null && row.getBookId() != null && !row.getBookId().isEmpty()) {
            Book bk = bookMapper.selectOne(new LambdaQueryWrapper<Book>().eq(Book::getBookId, row.getBookId()));
            if (bk != null) {
                Map<String, Object> book = bookService.toBookJson(bk);
                materializeShareCoverIfNeeded(code, book, origin);
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("payload", book);
                return res;
            }
        }
        // 4) shares payload 兜底
        if (row != null && row.getPayload() != null && !row.getPayload().isEmpty()) {
            Object book;
            try {
                book = MAPPER.readValue(row.getPayload(), new TypeReference<Object>() {});
            } catch (Exception e) {
                book = null;
            }
            if (book instanceof Map) {
                materializeShareCoverIfNeeded(code, (Map<String, Object>) book, origin);
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("payload", book);
                return res;
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("payload", row.getPayload());
            return res;
        }
        throw new ApiException(404, "这个索书号的书，书屋的架子上找不到……是不是抄错了？");
    }

    @SuppressWarnings("unchecked")
    private void materializeShareCoverIfNeeded(String code, Map<String, Object> book, String origin) {
        Object cover = book.get("cover");
        if (cover instanceof String s && s.startsWith("data:image/")) {
            boolean ready = assetStore.shareCoverOnDisk(code) || assetStore.materializeShareCover(code, s);
            if (ready) book.put("cover", origin + "/shares/" + encode(code) + ".jpg");
        }
    }

    /** 复刻 shareCover.js requestOrigin：尊重 x-forwarded-proto / host */
    public static String requestOrigin(HttpServletRequest req) {
        String host = req.getHeader("host");
        if (host == null || host.isEmpty()) host = "mist-mo.top";
        host = host.split(",")[0].trim();
        String fwd = req.getHeader("x-forwarded-proto");
        String proto = "http";
        if (fwd != null && !fwd.isEmpty()) {
            String first = fwd.split(",")[0].trim();
            if (first.equals("https") || first.equals("http")) proto = first;
        }
        return proto + "://" + host;
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
