package com.sylvia.bookhouse.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sylvia.bookhouse.mapper.BookMapper;
import com.sylvia.bookhouse.mapper.ShareMapper;
import com.sylvia.bookhouse.model.Book;
import com.sylvia.bookhouse.model.Share;
import com.sylvia.bookhouse.util.AssetStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * v3.1 荐书短链 /s/:code：微信转发卡片靠服务端直出 og 元信息（爬虫不执行 JS）。
 * 兼容 v4.0.4：shares 无记录时直接查 books 表（官方书 baiyan 与自创书 custom_xxx 都能出卡片）。
 */
@RestController
public class SharePageController {

    private static final Pattern SHARE_CODE_RE = Pattern.compile("^[\\u4e00-\\u9fa5a-zA-Z0-9_-]{1,64}$");

    private final ShareMapper shareMapper;
    private final BookMapper bookMapper;
    private final AssetStore assetStore;
    private final String publicDir;

    public SharePageController(ShareMapper shareMapper, BookMapper bookMapper, AssetStore assetStore,
                               @Value("${sylvia.public-dir:./public}") String publicDir) {
        this.shareMapper = shareMapper;
        this.bookMapper = bookMapper;
        this.assetStore = assetStore;
        this.publicDir = publicDir;
    }

    private static String htmlEscape(Object s) {
        return String.valueOf(s == null ? "" : s)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String requestOrigin(HttpServletRequest req) {
        String proto = req.getHeader("x-forwarded-proto");
        if (proto == null || proto.isEmpty()) proto = "http";
        String host = req.getHeader("x-forwarded-host");
        if (host == null || host.isEmpty()) host = req.getHeader("host");
        if (host == null || host.isEmpty()) host = "localhost";
        return proto + "://" + host;
    }

    private static String defaultOgTags(HttpServletRequest req) {
        String base = requestOrigin(req);
        String title = "Sylvia · 森林书屋——每本书里，都有一个等你的人";
        String desc = "森林深处有一间书屋，架上每一本书里都住着一个人。推开那扇木门，翻开属于你的那本，故事就会为你开始。";
        String img = base + "/og-default.jpg";
        return "<meta property=\"og:type\" content=\"website\">\n" +
                "    <meta property=\"og:title\" content=\"" + htmlEscape(title) + "\">\n" +
                "    <meta property=\"og:description\" content=\"" + htmlEscape(desc) + "\">\n" +
                "    <meta property=\"og:image\" content=\"" + htmlEscape(img) + "\">\n" +
                "    <meta property=\"og:image:width\" content=\"720\">\n" +
                "    <meta property=\"og:image:height\" content=\"1280\">\n" +
                "    <meta name=\"twitter:card\" content=\"summary_large_image\">\n" +
                "    <meta name=\"twitter:title\" content=\"" + htmlEscape(title) + "\">\n" +
                "    <meta name=\"twitter:description\" content=\"" + htmlEscape(desc) + "\">\n" +
                "    <meta name=\"twitter:image\" content=\"" + htmlEscape(img) + "\">";
    }

    private void readIndex(HttpServletRequest req, HttpServletResponse resp, String injectHead) throws IOException {
        Path index = Paths.get(publicDir, "index.html");
        byte[] data;
        try {
            data = Files.readAllBytes(index);
        } catch (Exception e) {
            resp.setStatus(200);
            resp.setContentType("text/html; charset=utf-8");
            resp.setHeader("Cache-Control", "no-cache");
            resp.getWriter().write("<!doctype html><meta charset=\"utf-8\">Sylvia 森林书屋");
            return;
        }
        String html = new String(data, StandardCharsets.UTF_8);
        if (injectHead != null && !injectHead.isEmpty()) {
            html = html.replaceFirst("<head>", "<head>\n    " + injectHead + "\n");
        }
        resp.setStatus(200);
        resp.setContentType("text/html; charset=utf-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/s/{code}")
    public void serveSharePage(@PathVariable("code") String rawCode,
                               HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String code = rawCode;
        if (!SHARE_CODE_RE.matcher(code).matches()) {
            readIndex(req, resp, defaultOgTags(req));
            return;
        }

        Share row = null;
        try {
            row = shareMapper.selectOne(new LambdaQueryWrapper<Share>().eq(Share::getCode, code));
        } catch (Exception ignored) {
            row = null;
        }

        // v4.0.4：shares 无记录时直接查 books 表（官方书 book_id 如 baiyan、自创书 custom_xxx 都能出卡片）
        Map<String, Object> book = null;
        if (row == null) {
            try {
                Book bk = bookMapper.selectOne(new LambdaQueryWrapper<Book>().eq(Book::getBookId, code));
                if (bk != null) {
                    book = Map.of("id", String.valueOf(bk.getBookId()), "name", str(bk.getTitle()),
                            "intro", str(bk.getIntro()), "cardDesc", str(bk.getCardDesc()), "cover", str(bk.getCoverUrl()));
                }
            } catch (Exception ignored) {
                book = null;
            }
        }

        if (book == null && row != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = (Map<String, Object>) assetStore.parseJson(row.getPayload());
                if (parsed != null) book = parsed;
            } catch (Exception ignored) {
                book = null;
            }
        }
        if (book == null && row != null && row.getBookId() != null && !row.getBookId().isEmpty()) {
            try {
                Book bk = bookMapper.selectOne(new LambdaQueryWrapper<Book>().eq(Book::getBookId, row.getBookId()));
                if (bk != null) {
                    book = Map.of("id", String.valueOf(bk.getBookId()), "name", str(bk.getTitle()),
                            "intro", str(bk.getIntro()), "cardDesc", str(bk.getCardDesc()), "cover", str(bk.getCoverUrl()));
                }
            } catch (Exception ignored) {
                book = null;
            }
        }
        if (book == null) {
            readIndex(req, resp, defaultOgTags(req));
            return;
        }

        String base = requestOrigin(req);
        String name = str(book.get("name")).trim();
        if (name.length() > 30) name = name.substring(0, 30);
        if (name.isEmpty()) name = "无名之书";
        String desc = str(book.get("intro"));
        if (desc.isEmpty()) desc = str(book.get("cardDesc"));
        desc = desc.replaceAll("\\s+", " ").trim();
        if (desc.length() > 60) desc = desc.substring(0, 60);
        if (desc.isEmpty()) desc = "一本书正在森林书屋等你翻开";

        String ogImage = "";
        String cover = str(book.get("cover"));
        if (cover.startsWith("data:image/")) {
            if (assetStore.materializeShareCover(code, cover)) ogImage = base + "/shares/" + code + ".jpg";
        } else if (cover.startsWith("/assets/")) {
            ogImage = base + cover;
        } else if (cover.matches("^https?://.*")) {
            ogImage = cover;
        }

        String ogTitle = "给你推荐了一本书，名字叫《" + name + "》";
        StringBuilder sb = new StringBuilder();
        sb.append("<meta property=\"og:type\" content=\"article\">\n");
        sb.append("    <meta property=\"og:title\" content=\"").append(htmlEscape(ogTitle)).append("\">\n");
        sb.append("    <meta property=\"og:description\" content=\"").append(htmlEscape(desc)).append("\">\n");
        if (!ogImage.isEmpty()) {
            sb.append("    <meta property=\"og:image\" content=\"").append(htmlEscape(ogImage)).append("\">\n");
            sb.append("    <meta property=\"og:image:width\" content=\"820\">\n");
            sb.append("    <meta property=\"og:image:height\" content=\"1160\">\n");
        }
        sb.append("    <meta itemprop=\"name\" content=\"").append(htmlEscape(ogTitle)).append("\">\n");
        if (!ogImage.isEmpty()) {
            sb.append("    <meta itemprop=\"image\" content=\"").append(htmlEscape(ogImage)).append("\">\n");
        }
        sb.append("    <meta name=\"twitter:card\" content=\"summary_large_image\">\n");
        sb.append("    <meta name=\"twitter:title\" content=\"").append(htmlEscape(ogTitle)).append("\">\n");
        sb.append("    <meta name=\"twitter:description\" content=\"").append(htmlEscape(desc)).append("\">\n");
        if (!ogImage.isEmpty()) {
            sb.append("    <meta name=\"twitter:image\" content=\"").append(htmlEscape(ogImage)).append("\">");
        }
        readIndex(req, resp, sb.toString());
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
