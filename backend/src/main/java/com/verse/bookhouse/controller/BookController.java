package com.sylvia.bookhouse.controller;

import com.sylvia.bookhouse.exception.ApiException;
import com.sylvia.bookhouse.security.UserContext;
import com.sylvia.bookhouse.service.BookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * /api/books 全部端点（与 Node routes/api.js 兼容）。
 *  - GET /api/books：官方书列表（匿名）
 *  - GET /api/books/mine：我的自创书（登录）
 *  - GET /api/books/:id：官方书详情（匿名；自创书走 /api/share/:code）
 *  - POST /api/books：创建自创书（登录）
 *  - PUT /api/books/:id：部分更新（登录，作者本人）
 *  - DELETE /api/books/:id：删除联动（登录，作者本人；官方书 403）
 */
@RestController
@RequestMapping("/api/books")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        return ResponseEntity.ok(Map.of("books", bookService.listOfficial()));
    }

    @GetMapping("/mine")
    public ResponseEntity<Map<String, Object>> mine() {
        List<Map<String, Object>> books = bookService.listMine(UserContext.uid());
        return ResponseEntity.ok(Map.of("books", books));
    }

    @GetMapping("/{bookId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String bookId) {
        Map<String, Object> book = bookService.getOfficial(bookId);
        if (book == null) throw new ApiException(404, "这本书的架子上没有……是不是走错房间了？");
        return ResponseEntity.ok(Map.of("book", book));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> book = bookService.create(body, UserContext.uid());
        return ResponseEntity.status(201).body(Map.of("ok", true, "book", book));
    }

    @PutMapping("/{bookId}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String bookId,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> book = bookService.update(bookId, UserContext.uid(), body);
        return ResponseEntity.ok(Map.of("ok", true, "book", book));
    }

    @DeleteMapping("/{bookId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String bookId) {
        bookService.delete(bookId, UserContext.uid());
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
