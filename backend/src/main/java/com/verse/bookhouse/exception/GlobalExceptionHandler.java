package com.sylvia.bookhouse.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * 全局异常 → 与 Node 一致的 {error: msg} JSON。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        // 【增加一行日志】
        log.warn("业务异常拦截: status={}, message={}", e.status, e.getMessage());
        
        return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
    }

    /** 静态资源不存在 → 404（与 Node serveStatic 一致，不弹 500） */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noResource(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", "not found"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        // 【增加一行日志】
        log.warn("参数校验异常: {}", e.getMessage());
        
        return ResponseEntity.badRequest().body(Map.of("error", "请求的格式不太对，书屋没能看懂"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("unhandled error", e);
        return ResponseEntity.status(500).body(Map.of("error", "书屋里出了点小状况，稍后再试试"));
    }
        @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxSizeException(Exception e) {
        log.warn("文件上传过大: {}", e.getMessage());
        return ResponseEntity.status(413).body(Map.of("error", "上传的文件太大了，请压缩后再试"));
    }
}
