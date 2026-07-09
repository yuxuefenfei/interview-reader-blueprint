package com.example.interviewreader;

import java.nio.file.Files;
import java.nio.file.Path;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.interviewreader.persistence.mapper")
public class InterviewReaderApplication {
    private static final String PDFBOX_FONT_CACHE_PROPERTY = "pdfbox.fontcache";

    static {
        configurePdfBoxFontCache();
    }

    public static void main(String[] args) {
        SpringApplication.run(InterviewReaderApplication.class, args);
    }

    private static void configurePdfBoxFontCache() {
        if (System.getProperty(PDFBOX_FONT_CACHE_PROPERTY) != null) {
            return;
        }
        var fontCache = Path.of(System.getProperty("user.dir"), "target", "pdfbox-font-cache");
        try {
            Files.createDirectories(fontCache);
            System.setProperty(PDFBOX_FONT_CACHE_PROPERTY, fontCache.toString());
        } catch (Exception exception) {
            var fallback = Path.of(System.getProperty("java.io.tmpdir"), "interview-reader-pdfbox-font-cache");
            System.setProperty(PDFBOX_FONT_CACHE_PROPERTY, fallback.toString());
        }
    }
}
