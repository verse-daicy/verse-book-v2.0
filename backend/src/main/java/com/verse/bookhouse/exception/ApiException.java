package com.sylvia.bookhouse.exception;

/**
 * 业务异常：携带 HTTP 状态码，由 GlobalExceptionHandler 统一转成 {error: msg}。
 */
public class ApiException extends RuntimeException {
    public final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }
}
