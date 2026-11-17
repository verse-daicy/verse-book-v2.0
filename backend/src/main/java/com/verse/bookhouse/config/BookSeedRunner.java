package com.sylvia.bookhouse.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sylvia.bookhouse.mapper.BookMapper;
import com.sylvia.bookhouse.model.Book;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * 官方书启动初始化（seed）：
 * - 数据源：classpath:/seed/official-books.json（v4.2 含 22 本：白晏 baiyan / 秋立阳 qiuliyang + 20 位男主）
 * - 幂等：按 book_id + owner_id IS NULL 判空，已存在则跳过，可重复执行
 * - v4.2：coverUrl/scenes 存 /covers/ /assets/ 服务器静态 URL（WebConfig 已映射 public-dir），前端 resolveAsset 直接透传
 * - v4.2：启动前 schema ensure（自动补 last_read_at / world_view 列，幂等），保证老库/新库都能排序与存世界观
 * - 失败仅记日志，不影响应用启动
 */
@Slf4j
@Component
public class BookSeedRunner implements CommandLineRunner {

    private final BookMapper bookMapper;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    public BookSeedRunner(BookMapper bookMapper, ObjectMapper objectMapper, DataSource dataSource) {
        this.bookMapper = bookMapper;
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
    }

    /** v4.2 schema ensure：books 表补 last_read_at / world_view 列（幂等，老库自动升级） */
    private void ensureSchema() {
        try (Connection conn = dataSource.getConnection()) {
            ensureColumn(conn, "last_read_at", "BIGINT NULL DEFAULT NULL");
            ensureColumn(conn, "world_view", "TEXT NULL");
        } catch (Exception e) {
            log.warn("[seed] schema ensure 失败（忽略，不影响启动）：{}", e.getMessage());
        }
    }

    private void ensureColumn(Connection conn, String col, String ddl) throws Exception {
        boolean exists;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'books' AND column_name = ?")) {
            ps.setString(1, col);
            try (ResultSet rs = ps.executeQuery()) {
                exists = rs.next();
            }
        }
        if (exists) return;
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE books ADD COLUMN " + col + " " + ddl);
        }
        log.info("[seed] schema ensure：books 表已补列 {}", col);
    }

    @Override
    public void run(String... args) {
        try {
            ensureSchema();
            InputStream in = new ClassPathResource("seed/official-books.json").getInputStream();
            List<Book> official = objectMapper.readValue(in, new TypeReference<List<Book>>() {});
            long now = System.currentTimeMillis();
            int inserted = 0;
            int skipped = 0;
            for (Book b : official) {
                if (b.getBookId() == null || b.getBookId().isEmpty()) {
                    continue;
                }
                Long exists = bookMapper.selectCount(new LambdaQueryWrapper<Book>()
                        .eq(Book::getBookId, b.getBookId())
                        .isNull(Book::getOwnerId));
                if (exists != null && exists > 0) {
                    skipped++;
                    continue;
                }
                b.setId(null);
                b.setOwnerId(null);
                if (b.getStatus() == null) {
                    b.setStatus(1);
                }
                b.setCreatedAt(now);
                b.setUpdatedAt(now);
                bookMapper.insert(b);
                inserted++;
                log.info("[seed] 官方书已入库：{}（{}）", b.getBookId(), b.getTitle());
            }
            log.info("[seed] 官方书初始化完成：新增 {} 本，已存在跳过 {} 本", inserted, skipped);
        } catch (Exception e) {
            log.error("[seed] 官方书初始化失败（不影响启动，可稍后手工导入）：{}", e.getMessage(), e);
        }
    }
}
