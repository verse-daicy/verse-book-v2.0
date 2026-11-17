package com.sylvia.bookhouse.service;

import com.sylvia.bookhouse.exception.ApiException;
import com.sylvia.bookhouse.util.ArkClient;
import com.sylvia.bookhouse.util.AssetStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 书屋绘书封（复刻 handleGenerateCover + buildCoverPrompt）。
 * 幂等①：public/shares/<key>.jpg 已裱好 → 读回旧稿交差（不调火山）；
 * 幂等②：同书正在画 → 在途去重；
 * 画成顺手落盘 shares/<key>.jpg（短链卡片同款书封）。
 */
@Service
public class CoverService {

    private static final Pattern SHARE_CODE_RE = Pattern.compile("^[\\u4e00-\\u9fa5a-zA-Z0-9_-]{1,64}$");

    private final ArkClient arkClient;
    private final AssetStore assetStore;

    public CoverService(ArkClient arkClient, AssetStore assetStore) {
        this.arkClient = arkClient;
        this.assetStore = assetStore;
    }

    public Map<String, Object> generate(Map<String, Object> body, long userId, HttpServletRequest req) {
        if (!arkClient.coverRateCheck(userId)) {
            throw new ApiException(429, "画师刚放下笔，缓一缓再请他动笔吧");
        }
        if (!arkClient.hasKey()) {
            throw new ApiException(503, "书屋的画师今天还没来，稍后再试试吧");
        }
        if (body == null) body = Map.of();
        String title = str(body.get("title"));
        String intro = str(body.get("intro"));
        Object tagsObj = body.get("tags");
        String emoji = str(body.get("emoji"));
        String bookKey = str(body.get("bookKey"));
        String key = SHARE_CODE_RE.matcher(bookKey == null ? "" : bookKey).matches() ? bookKey : "";

        // 幂等①
        if (!key.isEmpty() && assetStore.shareCoverOnDisk(key)) {
            String cached = assetStore.readShareCoverDataUrl(key);
            if (!cached.isEmpty()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("ok", true);
                r.put("cover", cached);
                r.put("contentType", "image/jpeg");
                r.put("cached", true);
                r.put("sizeBytes", (long) Math.floor(cached.length() * 0.75));
                r.put("url", ShareService.requestOrigin(req) + "/shares/" + encode(key) + ".jpg");
                return r;
            }
        }

        Out out;
        try {
            out = arkClient.inflightDedup("cover:" + key, () -> {
                // 并发时前一个请求可能已画好落盘
                if (!key.isEmpty() && assetStore.shareCoverOnDisk(key)) {
                    String cached = assetStore.readShareCoverDataUrl(key);
                    if (!cached.isEmpty()) return new Out(cached, "image/jpeg", true, 0);
                }
                try {
                    String prompt = buildCoverPrompt(title, intro, tagsObj, emoji);
                    String imageUrl = arkClient.generate(prompt, null, null);
                    ArkClient.DownloadResult d = arkClient.download(imageUrl);
                    String mime = d.mime();
                    String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(d.bytes);
                    return new Out(dataUrl, mime, false, d.bytes.length);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            throw new ApiException(502, "书屋的画师今天走神了，再试一次吧");
        }

        if (!out.cached && !key.isEmpty()) {
            try {
                assetStore.materializeShareCover(key, out.dataUrl);
            } catch (Exception ignored) {
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("cover", out.dataUrl);
        r.put("contentType", out.mime);
        r.put("sizeBytes", out.cached ? (long) Math.floor(out.dataUrl.length() * 0.75) : out.size);
        if (!key.isEmpty()) r.put("url", ShareService.requestOrigin(req) + "/shares/" + encode(key) + ".jpg");
        if (out.cached) r.put("cached", true);
        return r;
    }

    static String buildCoverPrompt(String title, String intro, Object tagsObj, String emoji) {
        StringBuilder parts = new StringBuilder();
        if (title != null && !title.isEmpty()) parts.append("故事名：").append(slice(title, 20));
        if (intro != null && !intro.isEmpty()) parts.append("扉页语：").append(slice(intro, 60));
        if (tagsObj instanceof java.util.List) {
            java.util.List<?> tags = (java.util.List<?>) tagsObj;
            java.util.List<String> keep = new java.util.ArrayList<>();
            for (Object t : tags) {
                if (t != null && !String.valueOf(t).isEmpty()) keep.add(String.valueOf(t));
                if (keep.size() >= 4) break;
            }
            if (!keep.isEmpty()) parts.append("标签：").append(String.join("、", keep));
        }
        if (emoji != null && !emoji.isEmpty()) parts.append("主角意象 emoji：").append(slice(emoji, 4));
        String theme = parts.length() > 0 ? parts.toString() : "一个未知的故事";
        return "一本小说的封面插画，竖版构图，画面中要呈现主角的意象和故事场景氛围，水彩与厚涂结合的插画质感，暖色调优先但服从故事气质，画面中严禁出现任何文字、字母、数字、标题或书名字样，只画画面不要画字。故事主题：" + theme + "。";
    }

    static String slice(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    static class Out {
        final String dataUrl;
        final String mime;
        final boolean cached;
        final int size;

        Out(String dataUrl, String mime, boolean cached, int size) {
            this.dataUrl = dataUrl;
            this.mime = mime;
            this.cached = cached;
            this.size = size;
        }
    }
}
