package com.sylvia.bookhouse.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sylvia.bookhouse.mapper.UserMapper;
import com.sylvia.bookhouse.model.User;
import com.sylvia.bookhouse.security.JwtUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 注册/登录/当前用户（复刻 routes/auth.js + middleware/auth.js）。
 * 密码校验用 BCryptPasswordEncoder 兼容存量 bcryptjs hash（$2a$）。
 */
@Service
public class AuthService {

    private static final Pattern USERNAME_RE = Pattern.compile("^[\\u4e00-\\u9fa5a-zA-Z0-9_]{2,16}$");

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final SaveService saveService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserMapper userMapper, JwtUtil jwtUtil, SaveService saveService) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.saveService = saveService;
    }

    /** @return null=校验通过；否则错误信息 */
    public String validateUsername(String username) {
        if (username == null || !USERNAME_RE.matcher(username).matches()) {
            return "名字需为 2-16 位中文、字母、数字或下划线";
        }
        return null;
    }

    public Map<String, Object> register(String username, String password) {
        Map<String, Object> res = new HashMap<>();
        String err = validateUsername(username);
        if (err != null) { res.put("code", 400); res.put("error", err); return res; }
        if (password == null || password.length() < 6) { res.put("code", 400); res.put("error", "密码至少 6 位"); return res; }
        Long existing = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (existing != null && existing > 0) { res.put("code", 409); res.put("error", "这个名字已被人写在书屋里了，换一个吧"); return res; }

        String hash = encoder.encode(password);
        long now = System.currentTimeMillis();
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(hash);
        u.setCreatedAt(now);
        userMapper.insert(u);

        // 新用户预置一条空存档（saves 表 uk_user 唯一）
        saveService.ensureSaveRow(u.getId());

        res.put("code", 200);
        res.put("token", jwtUtil.sign(u.getId(), username));
        res.put("username", username);
        return res;
    }

    public Map<String, Object> login(String username, String password) {
        Map<String, Object> res = new HashMap<>();
        User user = username == null ? null
                : userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username.trim()));
        if (user == null || password == null || !encoder.matches(password, user.getPasswordHash())) {
            res.put("code", 401); res.put("error", "名字或密码不对，书屋的门没有开"); return res;
        }
        res.put("code", 200);
        res.put("token", jwtUtil.sign(user.getId(), user.getUsername()));
        res.put("username", user.getUsername());
        return res;
    }

    public User findById(long id) {
        return userMapper.selectById(id);
    }
}
