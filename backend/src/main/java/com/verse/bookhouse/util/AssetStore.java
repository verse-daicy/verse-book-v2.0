package com.sylvia.bookhouse.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 书屋资产落盘工具（复刻 Node utils/sceneStore.js + utils/shareCover.js + materializeBookCoverToDisk）。
 * 约定：
 *  - 书封/插图 dataURL -> public/covers/<bookId>.jpg / <bookId>-<mood>.jpg，返回站内相对 URL
 *  - 荐书笺书封 dataURL -> public/shares/<code>.jpg
 *  - 五档插图 dataURL -> public/scenes/<bookKey>-<mood>.jpg
 */
@Component
public class AssetStore {

    private static final Pattern DATA_URL = Pattern.compile("^data:image/(jpe?g|png|webp);base64,(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCENE_KEY = Pattern.compile("^[\\u4e00-\\u9fa5a-zA-Z0-9_-]{1,64}$");
    private static final String[] SCENE_MOODS = {"calm", "bright", "crush", "sad", "dark"};
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int COVER_MAX_BYTES = 2 * 1024 * 1024;   // 书封上限 ~2MB
    private static final int SCENE_MAX_BYTES = 1536 * 1024;       // 插图上限 ~1.5MB

    private final String publicDir;

    public AssetStore(@Value("${sylvia.public-dir:./public}") String publicDir) {
        this.publicDir = publicDir;
    }

    public String publicDir() {
        return publicDir;
    }

    private Path dir(String sub) {
        Path p = Paths.get(publicDir, sub);
        try {
            Files.createDirectories(p);
        } catch (IOException ignored) {
        }
        return p;
    }

    private static byte[] decodeDataUrl(String dataUrl) {
        if (dataUrl == null) return null;
        Matcher m = DATA_URL.matcher(dataUrl.trim());
        if (!m.matches()) return null;
        try {
            return Base64.getDecoder().decode(m.group(2));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 自创书书封落盘到 covers/<fileName>；成功返回相对 URL，失败返回空串 */
    public String materializeCover(String fileName, String dataUrl) {
        byte[] buf = decodeDataUrl(dataUrl);
        if (buf == null || buf.length == 0 || buf.length > COVER_MAX_BYTES) return "";
        try {
            Files.write(dir("covers").resolve(fileName), buf);
            return "/covers/" + fileName;
        } catch (IOException e) {
            return "";
        }
    }

    /** 自创书封面/五档插图若是 dataURL，落盘换相对 URL（复刻 materializeBookAssets） */
    public java.util.Map<String, Object> materializeBookAssets(String bookId, java.util.Map<String, Object> book) {
        Object cover = book.get("cover");
        if (cover instanceof String s && s.startsWith("data:image/")) {
            String url = materializeCover(bookId + ".jpg", s);
            if (!url.isEmpty()) book.put("cover", url);
        }
        Object scenes = book.get("scenes");
        if (scenes instanceof java.util.Map<?, ?> sm) {
            for (java.util.Map.Entry<?, ?> e : sm.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String sv && sv.startsWith("data:image/")) {
                    String url = materializeCover(bookId + "-" + e.getKey() + ".jpg", sv);
                    if (!url.isEmpty()) ((Map<Object, Object>) sm).put(e.getKey(), url);
                }
            }
        }
        return book;
    }

    public boolean validSceneKey(String bookKey, String mood) {
        return bookKey != null && SCENE_KEY.matcher(bookKey).matches()
                && mood != null && java.util.Arrays.asList(SCENE_MOODS).contains(mood);
    }

    public boolean sceneOnDisk(String bookKey, String mood) {
        if (!validSceneKey(bookKey, mood)) return false;
        return Files.exists(Paths.get(publicDir, "scenes", bookKey + "-" + mood + ".jpg"));
    }

    /** 插图落盘（已存在则覆盖）；失败返回 false */
    public boolean materializeScene(String bookKey, String mood, String dataUrl) {
        if (!validSceneKey(bookKey, mood)) return false;
        byte[] buf = decodeDataUrl(dataUrl);
        if (buf == null || buf.length == 0 || buf.length > SCENE_MAX_BYTES) return false;
        try {
            Files.write(dir("scenes").resolve(bookKey + "-" + mood + ".jpg"), buf);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public String sceneUrl(String bookKey, String mood) {
        return "/scenes/" + encode(bookKey) + "-" + mood + ".jpg";
    }

    /** 读回 dataURL（生图命中旧画时交回前端） */
    public String sceneReadDataUrl(String bookKey, String mood) {
        if (!sceneOnDisk(bookKey, mood)) return "";
        try {
            byte[] buf = Files.readAllBytes(Paths.get(publicDir, "scenes", bookKey + "-" + mood + ".jpg"));
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(buf);
        } catch (IOException e) {
            return "";
        }
    }

    public boolean shareCoverOnDisk(String code) {
        return Files.exists(Paths.get(publicDir, "shares", code + ".jpg"));
    }

    /** 荐书笺书封落盘；已存在视为裱好不重复写 */
    /** JSON 字符串 → Map/List/Object；非法返回 null（复刻 Node JSON.parse 语义） */
    public Object parseJson(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return MAPPER.readValue(s, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean materializeShareCover(String code, String coverDataUrl) {
        if (coverDataUrl == null || !coverDataUrl.startsWith("data:image/")) return false;
        byte[] buf = decodeDataUrl(coverDataUrl);
        if (buf == null || buf.length == 0 || buf.length > COVER_MAX_BYTES) return false;
        Path fp = Paths.get(publicDir, "shares", code + ".jpg");
        if (Files.exists(fp)) return true;
        try {
            Files.write(dir("shares").resolve(code + ".jpg"), buf);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public String readShareCoverDataUrl(String code) {
        Path fp = Paths.get(publicDir, "shares", code + ".jpg");
        if (!Files.exists(fp)) return "";
        try {
            byte[] buf = Files.readAllBytes(fp);
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(buf);
        } catch (IOException e) {
            return "";
        }
    }

    /** 删除一本书相关的全部落盘文件（covers/scenes/shares），尽力而为 */
    public void deleteBookFiles(String bookId) {
        for (String sub : new String[]{"covers", "scenes", "shares"}) {
            Path d = Paths.get(publicDir, sub);
            if (!Files.isDirectory(d)) continue;
            try (var stream = Files.list(d)) {
                stream.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.equals(bookId + ".jpg")
                            || n.startsWith(bookId + "-")
                            || n.startsWith(bookId + ".");
                }).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
