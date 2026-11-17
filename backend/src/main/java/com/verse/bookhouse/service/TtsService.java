package com.sylvia.bookhouse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sylvia.bookhouse.exception.ApiException;
import com.sylvia.bookhouse.util.SimpleHttp;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 书屋说书人（复刻 routes/tts.js）：
 *  - 音色表 voices.json（外部文件优先，classpath 兜底）
 *  - 分性别物理过滤挑音色（LLM 挑 + 缓存 + 分性别兜底，三重保险）
 *  - 火山 Seed-TTS 2.0 单向流式合成，多段 JSON 拼接解析
 *  - 音频缓存 LRU + 署名限流
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------- 音色表 ----------
    private List<Map<String, Object>> voices = new ArrayList<>();
    private Set<String> voiceIds = Set.of();
    private String ttsEndpoint = "https://openspeech.bytedance.com/api/v3/tts/unidirectional";
    private String ttsResource = "seed-tts-2.0";

    private static final Map<String, String> FALLBACK_VOICE = Map.of(
            "male", "zh_male_gaolengchenwen_uranus_bigtts",
            "female", "zh_female_wenroushunv_uranus_bigtts",
            "child", "zh_female_xiaoxue_uranus_bigtts"
    );

    private static final Map<String, Integer> SPEECH_RATE = new HashMap<>(Map.of(
            "slow", -15, "normal", 0, "fast", 20, "standard", 0
    ));

    private static final Map<String, String> MOOD_EMOTION = new HashMap<>(Map.ofEntries(
            Map.entry("crush", "happy"), Map.entry("sad", "sad"), Map.entry("nervous", "fear"),
            Map.entry("happy", "happy"), Map.entry("calm", "neutral"),
            Map.entry("心动", "happy"), Map.entry("难过", "sad"), Map.entry("紧张", "fear"),
            Map.entry("危险", "fear"), Map.entry("害怕", "fear"), Map.entry("开心", "happy"),
            Map.entry("高兴", "happy"), Map.entry("元气", "happy"), Map.entry("温柔", "neutral"),
            Map.entry("平静", "neutral"), Map.entry("angry", "angry"), Map.entry("hate", "hate"),
            Map.entry("surprise", "surprised"), Map.entry("surprised", "surprised"),
            Map.entry("生气", "angry"), Map.entry("愤怒", "angry"), Map.entry("惊讶", "surprised"),
            Map.entry("disgust", "disgust")
    ));

    private static final Map<String, Integer> MOOD_RATE_DELTA = new HashMap<>(Map.ofEntries(
            Map.entry("sad", -12), Map.entry("nervous", 15), Map.entry("happy", 8),
            Map.entry("crush", 5), Map.entry("calm", -6), Map.entry("难过", -12),
            Map.entry("紧张", 15), Map.entry("危险", 15), Map.entry("害怕", 15),
            Map.entry("开心", 8), Map.entry("高兴", 8), Map.entry("元气", 8),
            Map.entry("心动", 5), Map.entry("温柔", -6), Map.entry("平静", -6),
            Map.entry("生气", 8), Map.entry("愤怒", 8), Map.entry("惊讶", 8)
    ));

    private static final int EMOTION_SCALE = 4;
    private static final int RATE_MIN = -25;
    private static final int RATE_MAX = 25;
    private static final int MAX_TEXT_LEN = 500;
    private static final int CACHE_MAX = 200;
    private static final int RATE_LIMIT = 30;
    private static final Pattern TTS_ID = Pattern.compile("[a-z]{2}_[a-z0-9_]+_uranus_bigtts");
    private static final Pattern SHORT_TAG = Pattern.compile("\\[(?:N|L|M|A|I|F|S|Q|/)[^\\]]*\\]");

    private final Environment env;

    private final Map<String, byte[]> audioCache = new java.util.LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > CACHE_MAX;
        }
    };
    private final Map<String, String> voicePickCache = new java.util.LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 500;
        }
    };
    private final Map<Long, long[]> rateMap = new HashMap<>();

    public TtsService(Environment env) {
        this.env = env;
        loadVoices();
    }

    @SuppressWarnings("unchecked")
    private void loadVoices() {
        String json = null;
        try {
            json = new String(Files.readAllBytes(Paths.get("./voices.json")), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        if (json == null) {
            try (InputStream in = new ClassPathResource("voices.json").getInputStream()) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("[tts] voices.json 读取失败", e);
            }
        }
        if (json == null) return;
        try {
            Map<String, Object> table = MAPPER.readValue(json, Map.class);
            Object v = table.get("voices");
            if (v instanceof List) voices = (List<Map<String, Object>>) v;
            Object rid = table.get("resourceId");
            if (rid != null) ttsResource = String.valueOf(rid);
            Object ep = table.get("endpoint");
            if (ep != null && !String.valueOf(ep).isEmpty()) ttsEndpoint = String.valueOf(ep);
            Set<String> ids = new java.util.HashSet<>();
            for (Map<String, Object> vv : voices) {
                if (vv.get("id") != null) ids.add(String.valueOf(vv.get("id")));
            }
            voiceIds = ids;
        } catch (Exception e) {
            log.warn("[tts] voices.json 解析失败", e);
        }
    }

    // ---------- 音色性别 ----------

    String voiceGender(String id) {
        for (Map<String, Object> v : voices) {
            if (id != null && id.equals(String.valueOf(v.get("id")))) {
                Object g = v.get("gender");
                if ("male".equals(g) || "female".equals(g) || "child".equals(g)) return (String) g;
            }
        }
        if (id != null && id.startsWith("zh_female_")) return "female";
        if (id != null && id.startsWith("zh_male_")) {
            return (id.contains("naiqimengwa") || id.contains("xiaoxue")) ? "child" : "male";
        }
        return "male";
    }

    boolean genderMatches(String voiceId, String want) {
        return voiceGender(voiceId).equals(want);
    }

    String fallbackVoice(String gender) {
        String id = FALLBACK_VOICE.getOrDefault(gender, FALLBACK_VOICE.get("male"));
        return voiceIds.contains(id) ? id : (voices.isEmpty() ? "" : String.valueOf(voices.get(0).get("id")));
    }

    String defaultVoice() {
        return fallbackVoice("male");
    }

    // ---------- 角色性别判定 ----------

    static String genderOfCharacter(String genderField, String personaText) {
        String g = String.valueOf(genderField == null ? "" : genderField).trim().toLowerCase();
        if (g.equals("male") || g.equals("female") || g.equals("child")) return g;
        if (g.matches("男|男性|男孩|少年|男士|男生")) return "male";
        if (g.matches("女|女性|女孩|少女|女士|女生")) return "female";
        if (g.contains("童") || g.contains("儿童") || g.contains("小孩")) return "child";
        String t = String.valueOf(personaText == null ? "" : personaText);
        if (t.length() > 600) t = t.substring(0, 600);
        if (t.matches(".*(她是|她叫|她名为|她的身份|女性|女主).*")) return "female";
        if (t.matches(".*(他是|他叫|他名为|他的身份|男性|男主).*")) return "male";
        if (t.matches(".*(姑娘|小姐|萝莉|萌妹|少御|御姐|妇人|阿姨|婆婆|妈妈|母亲).*")) return "female";
        if (t.matches(".*(公子|少爷|大叔|叔叔|爸爸|父亲|青年男|中年男).*")) return "male";
        return "male";
    }

    // ---------- 心情 → 情感/语速 ----------

    String moodEmotion(String mood) {
        if (mood == null) return "";
        String k = mood.trim().toLowerCase();
        String v = MOOD_EMOTION.get(k);
        return v == null ? (MOOD_EMOTION.get(mood.trim()) == null ? "" : MOOD_EMOTION.get(mood.trim())) : v;
    }

    int moodRateDelta(String mood) {
        if (mood == null) return 0;
        String k = mood.trim();
        Integer v = MOOD_RATE_DELTA.get(k.toLowerCase());
        return v == null ? (MOOD_RATE_DELTA.get(k) == null ? 0 : MOOD_RATE_DELTA.get(k)) : v;
    }

    // ---------- 限流 / 缓存 ----------

    synchronized boolean rateCheck(long uid) {
        long now = System.currentTimeMillis();
        long[] rec = rateMap.get(uid);
        if (rec == null || now - rec[0] >= 60_000) {
            rateMap.put(uid, new long[]{now, 1});
            return true;
        }
        if (rec[1] >= RATE_LIMIT) return false;
        rec[1] += 1;
        return true;
    }

    byte[] cacheGet(String key) {
        byte[] v = audioCache.remove(key);
        if (v == null) return null;
        audioCache.put(key, v);
        return v;
    }

    void cacheSet(String key, byte[] buf) {
        audioCache.put(key, buf);
    }

    private static String personaHash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(String.valueOf(s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    // ---------- TTS 专用 LLM 配置（默认 deepseek，与 Node tts.js 一致） ----------

    private String ttsLlmBaseUrl() {
        String v = env.getProperty("LLM_BASE_URL");
        if (v == null || v.isEmpty()) v = "https://api.deepseek.com/v1";
        return v.replaceAll("/+$", "");
    }

    private String ttsLlmApiKey() {
        String v = env.getProperty("LLM_API_KEY");
        return v == null ? "" : v;
    }

    private String ttsLlmModel() {
        String v = env.getProperty("LLM_MODEL");
        return (v == null || v.isEmpty()) ? "deepseek-chat" : v;
    }

    // ---------- 大模型挑音色 ----------

    private String llmPickVoice(String persona, String gender) {
        String apiKey = ttsLlmApiKey();
        if (apiKey.isEmpty()) return null;
        String want = gender == null || gender.isEmpty() ? "male" : gender;
        List<Map<String, Object>> pool = new ArrayList<>();
        for (Map<String, Object> v : voices) {
            Object g = v.get("gender");
            String vg = (g == null || g.toString().isEmpty()) ? voiceGender(String.valueOf(v.get("id"))) : String.valueOf(g);
            if (vg.equals(want)) pool.add(v);
        }
        List<Map<String, Object>> list = pool.isEmpty() ? voices : pool;
        StringBuilder lines = new StringBuilder();
        for (Map<String, Object> v : list) {
            String g = String.valueOf(v.get("gender"));
            String gw = "male".equals(g) ? "男" : ("female".equals(g) ? "女" : "童");
            lines.append(v.get("id")).append('|').append(gw).append('|')
                    .append(v.get("age") == null ? "" : v.get("age")).append('|')
                    .append(v.get("tone") == null ? "" : v.get("tone")).append('\n');
        }
        String genderWord = "female".equals(want) ? "女性" : ("child".equals(want) ? "孩童" : "男性");
        String sys = "你是配音导演，根据角色人设从音色表里挑一个最贴合的音色ID。只输出音色ID本身，不要任何解释、标点或markdown。";
        String personaSlice = String.valueOf(persona == null ? "" : persona);
        if (personaSlice.length() > 300) personaSlice = personaSlice.substring(0, 300);
        String user = "角色人设：" + personaSlice + "\n\n这是一个" + genderWord + "角色。\n"
                + "音色表（ID|性别|年龄感|调性，只能从下面这些ID里挑，性别必须与角色一致）：\n" + lines + "\n\n请输出最贴合该角色年龄、性别、性格的音色ID。";
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", ttsLlmModel());
            payload.put("stream", false);
            payload.put("temperature", 0.2);
            payload.put("max_tokens", 80);
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", sys));
            messages.add(Map.of("role", "user", "content", user));
            payload.put("messages", messages);
            String json = MAPPER.writeValueAsString(payload);
            HttpResponse<String> resp = SimpleHttp.postJson(ttsLlmBaseUrl() + "/chat/completions",
                    Map.of("Authorization", "Bearer " + apiKey), json, 20);
            if (resp.statusCode() != 200) return null;
            Map<String, Object> j = MAPPER.readValue(resp.body(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) j.get("choices");
            if (choices == null || choices.isEmpty()) return null;
            Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
            String content = String.valueOf(msg == null ? "" : msg.get("content"));
            Matcher m = TTS_ID.matcher(content);
            if (m.find() && voiceIds.contains(m.group()) && genderMatches(m.group(), want)) return m.group();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveVoice(String voice, String persona, String genderField) {
        String want = genderOfCharacter(genderField, persona);
        // 1) 前端给了音色且性别匹配才直接用；冲突时强制纠正（不动数据库）
        if (voice != null && !voice.isEmpty() && voiceIds.contains(voice) && genderMatches(voice, want)) return voice;
        // 2) 人设 hash 缓存（键带性别）
        String h = personaHash(want + "|" + persona);
        String cached = voicePickCache.get(h);
        if (cached != null) return cached;
        // 3) 请大模型挑
        String picked = null;
        try {
            picked = llmPickVoice(persona, want);
        } catch (Exception e) {
            picked = null;
        }
        // 4) 兜底
        if (picked == null || !genderMatches(picked, want)) picked = fallbackVoice(want);
        voicePickCache.put(h, picked);
        return picked;
    }

    // ---------- 火山合成（多段 JSON 拼接解析） ----------

    byte[] synthesize(String text, String speaker, String rateOpt, String mood) throws Exception {
        Map<String, Object> audioParams = new LinkedHashMap<>();
        audioParams.put("format", "mp3");
        audioParams.put("sample_rate", 24000);
        Integer rateBase = SPEECH_RATE.get(rateOpt == null ? "normal" : rateOpt);
        int rate = rateBase == null ? 0 : rateBase;
        rate = Math.max(RATE_MIN, Math.min(RATE_MAX, rate + moodRateDelta(mood)));
        if (rate != 0) audioParams.put("speech_rate", rate);
        String emo = moodEmotion(mood);
        if (!emo.isEmpty()) {
            audioParams.put("emotion", emo);
            audioParams.put("emotion_scale", EMOTION_SCALE);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", Map.of("uid", "sylvia-bookhouse"));
        body.put("req_params", Map.of("text", text, "speaker", speaker, "audio_params", audioParams));
        String json = MAPPER.writeValueAsString(body);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Api-Key", env.getProperty("TTS_API_KEY", ""));
        headers.put("X-Api-Resource-Id", ttsResource);
        headers.put("X-Api-Request-Id", UUID.randomUUID().toString());
        headers.put("X-Control-Require-Usage-Tokens-Return", "*");
        HttpResponse<String> resp = SimpleHttp.postJson(ttsEndpoint, headers, json, 30);
        if (resp.statusCode() != 200) {
            throw new RuntimeException("upstream http " + resp.statusCode());
        }
        TtsParse parsed = parseTtsBody(resp.body());
        if (parsed.audio.length == 0 || (parsed.lastCode != null && parsed.lastCode != 0 && parsed.lastCode != 20000000)) {
            throw new RuntimeException("synth failed code=" + parsed.lastCode);
        }
        return parsed.audio;
    }

    static class TtsParse {
        byte[] audio = new byte[0];
        Integer lastCode = null;
    }

    /** 响应体是多段 JSON 首尾拼接：data 为 base64 音频块，末块 code=20000000 */
    static TtsParse parseTtsBody(String raw) {
        TtsParse out = new TtsParse();
        java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
        String str = raw.trim();
        int p = 0;
        try {
            while (p < str.length()) {
                while (p < str.length() && Character.isWhitespace(str.charAt(p))) p++;
                if (p >= str.length() || str.charAt(p) != '{') { p++; continue; }
                int depth = 0, q = p;
                boolean inStr = false, esc = false;
                for (; q < str.length(); q++) {
                    char ch = str.charAt(q);
                    if (inStr) {
                        if (esc) esc = false;
                        else if (ch == '\\') esc = true;
                        else if (ch == '"') inStr = false;
                    } else {
                        if (ch == '"') inStr = true;
                        else if (ch == '{') depth++;
                        else if (ch == '}') { depth--; if (depth == 0) { q++; break; } }
                    }
                }
                if (q > str.length()) break;
                Map<String, Object> obj = MAPPER.readValue(str.substring(p, q), Map.class);
                p = q;
                Object data = obj.get("data");
                if (data != null) acc.write(Base64.getDecoder().decode(String.valueOf(data)));
                Object code = obj.get("code");
                if (code instanceof Number) out.lastCode = ((Number) code).intValue();
            }
        } catch (Exception e) {
            throw new RuntimeException("bad response", e);
        }
        out.audio = acc.toByteArray();
        return out;
    }

    // ---------- 路由：POST /api/tts ----------

    public void handleTts(Map<String, Object> body, HttpServletResponse response) throws Exception {
        String apiKey = env.getProperty("TTS_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ApiException(503, "书屋今天没人替你念书，稍后再来吧");
        }
        if (body == null) body = Map.of();
        long uid = com.sylvia.bookhouse.security.UserContext.uid();
        if (!rateCheck(uid)) {
            throw new ApiException(429, "今天想请人念的话太多了，喝口茶歇会儿再点吧");
        }

        String text = String.valueOf(body.get("text") == null ? "" : body.get("text")).trim();
        if (text.isEmpty()) throw new ApiException(400, "这句话没什么好念的");
        text = SHORT_TAG.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").trim();
        if (text.length() > MAX_TEXT_LEN) text = text.substring(0, MAX_TEXT_LEN);
        if (text.isEmpty()) throw new ApiException(400, "这句话没什么好念的");

        String reqVoice = body.get("voice") instanceof String ? (String) body.get("voice") : "";
        String persona = body.get("persona") instanceof String ? (String) body.get("persona") : "";
        String reqGender = body.get("gender") instanceof String ? (String) body.get("gender") : "";
        Object rateObj = body.get("rate");
        String reqRate = rateObj instanceof String ? (String) rateObj
                : (body.get("speechRate") instanceof String ? (String) body.get("speechRate") : "normal");
        String rate = SPEECH_RATE.containsKey(reqRate) ? reqRate : "normal";
        String mood = body.get("mood") instanceof String ? ((String) body.get("mood")).substring(0, Math.min(12, ((String) body.get("mood")).length())) : "";

        String voice;
        try {
            voice = resolveVoice(reqVoice, persona, reqGender);
        } catch (Exception e) {
            voice = fallbackVoice(genderOfCharacter(reqGender, persona));
        }

        String cacheKey = voice + "|" + rate + "|" + (mood.isEmpty() ? "n" : mood) + "|" + text;
        byte[] audio = cacheGet(cacheKey);
        if (audio == null) {
            try {
                audio = synthesize(text, voice, rate, mood);
                cacheSet(cacheKey, audio);
            } catch (Exception e) {
                log.warn("[tts] synth error", e);
                throw new ApiException(502, "念书的人临时嗓子哑了，稍后再请吧");
            }
        }

        response.setStatus(200);
        response.setContentType("audio/mpeg");
        response.setContentLength(audio.length);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Voice", voice);
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Expose-Headers", "X-Voice");
        response.getOutputStream().write(audio);
        response.getOutputStream().flush();
    }
}
