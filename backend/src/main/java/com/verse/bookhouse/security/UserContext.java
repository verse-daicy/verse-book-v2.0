package com.sylvia.bookhouse.security;

/**
 * 当前登录用户上下文（ThreadLocal，由 AuthInterceptor 写入，请求结束清除）。
 */
public final class UserContext {

    private static final ThreadLocal<Long> UID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    private UserContext() {}

    public static void set(long uid, String username) {
        UID.set(uid);
        USERNAME.set(username);
    }

    public static Long uid() {
        return UID.get();
    }

    public static String username() {
        return USERNAME.get();
    }

    public static boolean loggedIn() {
        return UID.get() != null;
    }

    public static void clear() {
        UID.remove();
        USERNAME.remove();
    }
}
