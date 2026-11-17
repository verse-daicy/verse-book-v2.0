package com.sylvia.bookhouse.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sylvia.bookhouse.mapper.UserMapper;
import com.sylvia.bookhouse.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 鉴权拦截器（复刻 routes/api.js handleApi 的匿名/受保护路由规则 + middleware/auth.js authRequired）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    public AuthInterceptor(JwtUtil jwtUtil, UserMapper userMapper) {
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (!path.startsWith("/api/")) return true;
        // CORS 预检不鉴权（Node 对 OPTIONS 直接回 204）
        if ("OPTIONS".equals(method)) return true;
        if (isAnonymous(method, path)) return true;

        // 需要鉴权
        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : "";
        Map<String, Object> claims = jwtUtil.verify(token);
        if (claims == null) {
            writeJson(response, 401, "书屋的门关上了，请重新登录");
            return false;
        }
        Object uidObj = claims.get("uid");
        if (!(uidObj instanceof Number)) {
            writeJson(response, 401, "书屋的门关上了，请重新登录");
            return false;
        }
        long uid = ((Number) uidObj).longValue();
        User row = userMapper.selectById(uid);
        if (row == null) {
            writeJson(response, 401, "书屋的名录更新过，请重新署名推开门");
            return false;
        }
        UserContext.set(uid, row.getUsername());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    /** 与 Node handleApi 匿名规则一一对应 */
    static boolean isAnonymous(String method, String path) {
        // GET /api/share/:code
        if ("GET".equals(method) && path.matches("^/api/share/[^/]+$")) return true;
        // GET /api/scenes/art/:bookKey/:mood
        if ("GET".equals(method) && path.matches("^/api/scenes/art/[^/]+/[^/]+$")) return true;
        // GET /api/books（官方书列表，匿名可读）
        if ("GET".equals(method) && "/api/books".equals(path)) return true;
        // GET /api/books/:id（除 mine）
        if ("GET".equals(method) && path.matches("^/api/books/[^/]+$") && !path.endsWith("/mine")) return true;
        // POST /api/auth/register 与 /api/auth/login
        if ("POST".equals(method) && (path.equals("/api/auth/register") || path.equals("/api/auth/login"))) return true;
        return false;
    }

    private static void writeJson(HttpServletResponse response, int status, String msg) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json; charset=utf-8");
        response.getWriter().write(MAPPER.writeValueAsString(Map.of("error", msg)));
        response.getWriter().flush();
    }
}
