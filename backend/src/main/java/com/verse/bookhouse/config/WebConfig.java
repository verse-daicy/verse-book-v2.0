package com.sylvia.bookhouse.config;

import com.sylvia.bookhouse.security.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;
import java.time.Duration;

/**
 * Web 配置：CORS + 静态资源（public/）+ 鉴权拦截器。
 * 缓存头与 Node server.js 一致：assets/shares/scenes immutable 1 年、covers 30 天、html no-cache。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final String publicDir;

    public WebConfig(AuthInterceptor authInterceptor, @Value("${sylvia.public-dir:./public}") String publicDir) {
        this.authInterceptor = authInterceptor;
        this.publicDir = publicDir;
    }

    private String loc(String sub) {
        return Paths.get(publicDir, sub).toAbsolutePath().normalize().toUri().toString();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization")
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**").addResourceLocations(loc("assets"))
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/shares/**").addResourceLocations(loc("shares"))
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/scenes/**").addResourceLocations(loc("scenes"))
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/covers/**").addResourceLocations(loc("covers"))
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());
        registry.addResourceHandler("/**").addResourceLocations(loc(""))
                .setCacheControl(CacheControl.noCache());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/**");
    }

    /**
     * 根路径 forward 到 index.html：自定义 resource handler 的外部目录不参与
     * Spring Boot welcome page 解析，需显式指定，否则访问 / 会 404。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
