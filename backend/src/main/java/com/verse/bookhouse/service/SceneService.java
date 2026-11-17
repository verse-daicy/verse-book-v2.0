package com.sylvia.bookhouse.service;

import com.sylvia.bookhouse.exception.ApiException;
import com.sylvia.bookhouse.util.ArkClient;
import com.sylvia.bookhouse.util.AssetStore;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 书屋绘书内插图 + 画师留底（复刻 handleGenerateScene/handleGetSceneArt/handlePutSceneArt）。
 * 五档情绪：calm/bright/crush/sad/dark；同书同档全局只画一次（落盘幂等 + 在途去重）。
 */
@Service
public class SceneService {

    private static final Pattern SHARE_CODE_RE = Pattern.compile("^[\\u4e00-\\u9fa5a-zA-Z0-9_-]{1,64}$");
    private static final Pattern DATA_URL_IMG = Pattern.compile("^data:image/(jpe?g|png|webp);base64,[a-z0-9+/=]+$", Pattern.CASE_INSENSITIVE);
    private static final long REF_IMAGE_MAX = 3_000_000L;

    private static final Map<String, String> MOOD_SCENE_GUIDE = Map.of(
            "calm", "温柔日常：暖柔的光线，安静的室内或日常场景，色调平和温暖，柔和的自然光，闲适安静的氛围；角色神情温柔放松，带着浅浅笑意",
            "bright", "明快活泼：明亮通透的光线，阳光充足，轻快开阔的场景，高饱和度暖色调，轻盈有活力的氛围；角色神情明朗，开怀地笑，眼睛发亮",
            "crush", "暧昧心动：暖黄色灯光，黄昏或烛光，粉橙暖色调，心跳般的柔光，浪漫暧昧的氛围；角色羞涩心动，脸颊泛红，眼神躲闪又偷瞄，耳尖发红",
            "sad", "低落难过：雨天窗边、冷掉的茶、阴天，蓝灰冷色调，光线昏暗压抑，沉默忧伤的氛围；角色垂着眼，神情失落难过，眼眶微红",
            "dark", "暗调悬念：月夜、浓重的影子、雾气，强烈的明暗对比暗调，紧张不安的电影氛围；角色神情紧张警觉，眉眼凝重，像察觉到了什么"
    );
    private static final List<String> MOOD_ORDER = List.of("calm", "bright", "crush", "sad", "dark");

    private final ArkClient arkClient;
    private final AssetStore assetStore;

    public SceneService(ArkClient arkClient, AssetStore assetStore) {
        this.arkClient = arkClient;
        this.assetStore = assetStore;
    }

    public Map<String, Object> generateScene(Map<String, Object> body, long userId) {
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
        String mood = str(body.get("mood"));
        String refCover = str(body.get("refCover"));
        String bookKey = str(body.get("bookKey"));
        String moodKey = MOOD_ORDER.contains(mood) ? mood : "calm";
        String key = SHARE_CODE_RE.matcher(bookKey == null ? "" : bookKey).matches() ? bookKey : "";

        // 幂等①
        if (!key.isEmpty() && assetStore.sceneOnDisk(key, moodKey)) {
            String cached = assetStore.sceneReadDataUrl(key, moodKey);
            if (!cached.isEmpty()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("ok", true);
                r.put("mood", moodKey);
                r.put("image", cached);
                r.put("contentType", "image/jpeg");
                r.put("sizeBytes", (long) Math.floor(cached.length() * 0.75));
                r.put("cached", true);
                r.put("url", assetStore.sceneUrl(key, moodKey));
                r.put("refUsed", false);
                return r;
            }
        }

        Out out;
        try {
            out = arkClient.inflightDedup("scene:" + key + ":" + moodKey, () -> {
                if (!key.isEmpty() && assetStore.sceneOnDisk(key, moodKey)) {
                    String cached = assetStore.sceneReadDataUrl(key, moodKey);
                    if (!cached.isEmpty()) return new Out(cached, "image/jpeg", true, 0, false);
                }
                try {
                    String prompt = buildScenePrompt(title, intro, tagsObj, moodKey);
                    List<String> images = new ArrayList<>();
                    if (refCover != null) {
                        String s = refCover.trim();
                        if (s.startsWith("http://") || s.startsWith("https://")) images.add(s);
                        else if (DATA_URL_IMG.matcher(s).matches() && s.length() <= REF_IMAGE_MAX) images.add(s);
                    }
                    // 省钱硬约束：先 720x1280，被拒退 864x1536（唯一一次重试）
                    String imageUrl;
                    try {
                        imageUrl = arkClient.generate(prompt, "720x1280", images);
                    } catch (Exception e1) {
                        imageUrl = arkClient.generate(prompt, "864x1536", images);
                    }
                    ArkClient.DownloadResult d = arkClient.download(imageUrl);
                    String mime = d.mime();
                    String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(d.bytes);
                    return new Out(dataUrl, mime, false, d.bytes.length, !images.isEmpty());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            throw new ApiException(502, "书屋的画师今天走神了，再试一次吧");
        }

        if (!out.cached && !key.isEmpty()) {
            try {
                assetStore.materializeScene(key, moodKey, out.dataUrl);
            } catch (Exception ignored) {
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("mood", moodKey);
        r.put("image", out.dataUrl);
        r.put("contentType", out.mime);
        r.put("refUsed", out.refUsed);
        r.put("sizeBytes", out.cached ? (long) Math.floor(out.dataUrl.length() * 0.75) : out.size);
        if (!key.isEmpty()) r.put("url", assetStore.sceneUrl(key, moodKey));
        if (out.cached) r.put("cached", true);
        return r;
    }

    // ---------- 画师留底 ----------

    public Map<String, Object> getSceneArt(String rawBookKey, String rawMood) {
        String bookKey = decode(rawBookKey);
        String mood = decode(rawMood == null ? "" : rawMood).replaceAll("\\.jpg$", "");
        if (!assetStore.validSceneKey(bookKey, mood) || !assetStore.sceneOnDisk(bookKey, mood)) {
            throw new ApiException(404, "not_yet");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("url", assetStore.sceneUrl(bookKey, mood));
        return r;
    }

    public Map<String, Object> putSceneArt(Map<String, Object> body) {
        if (body == null) body = Map.of();
        String bookKey = str(body.get("bookKey")).trim();
        String mood = str(body.get("mood")).trim();
        String image = str(body.get("image"));
        if (!SHARE_CODE_RE.matcher(bookKey).matches()) {
            throw new ApiException(400, "索书号的写法不太对，书屋登记不上");
        }
        if (!MOOD_ORDER.contains(mood)) {
            throw new ApiException(400, "没有这一档画");
        }
        if (!image.startsWith("data:image/")) {
            throw new ApiException(400, "画稿格式不对，裱不进书屋");
        }
        boolean ok = assetStore.materializeScene(bookKey, mood, image);
        if (!ok) throw new ApiException(413, "画稿太大了，裱不进书屋");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("url", assetStore.sceneUrl(bookKey, mood));
        return r;
    }

    static String buildScenePrompt(String title, String intro, Object tagsObj, String mood) {
        StringBuilder parts = new StringBuilder();
        if (title != null && !title.isEmpty()) parts.append("故事名：").append(CoverService.slice(title, 20));
        if (intro != null && !intro.isEmpty()) parts.append("扉页语：").append(CoverService.slice(intro, 60));
        if (tagsObj instanceof java.util.List) {
            java.util.List<?> tags = (java.util.List<?>) tagsObj;
            java.util.List<String> keep = new java.util.ArrayList<>();
            for (Object t : tags) {
                if (t != null && !String.valueOf(t).isEmpty()) keep.add(String.valueOf(t));
                if (keep.size() >= 4) break;
            }
            if (!keep.isEmpty()) parts.append("标签：").append(String.join("、", keep));
        }
        String theme = parts.length() > 0 ? parts.toString() : "一个未知的故事";
        String guide = MOOD_SCENE_GUIDE.getOrDefault(mood, MOOD_SCENE_GUIDE.get("calm"));
        return "9:16 竖版手机全屏故事氛围插画，电影感光影，水彩与厚涂之间的插画质感，柔光，竖构图。\n"
                + "画面以书中角色为主体，可以画角色的正脸或半身近景；角色的发色、发型、瞳色、年龄感与气质必须与参考书封上的角色完全一致，是同一个人。\n"
                + "本档情绪与表情：" + guide + "。\n"
                + "场景与故事呼应（" + theme + "），氛围色调随情绪走，用光影和环境烘托心情。\n"
                + "画面中严禁出现任何文字、字母、数字、标题或字样，只画画面不要画字。";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    static class Out {
        final String dataUrl;
        final String mime;
        final boolean cached;
        final int size;
        final boolean refUsed;

        Out(String dataUrl, String mime, boolean cached, int size, boolean refUsed) {
            this.dataUrl = dataUrl;
            this.mime = mime;
            this.cached = cached;
            this.size = size;
            this.refUsed = refUsed;
        }
    }
}
