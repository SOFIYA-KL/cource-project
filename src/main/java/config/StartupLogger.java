package com.standupwiki.standup_wiki.config;

import com.standupwiki.standup_wiki.service.CacheService;
import com.standupwiki.standup_wiki.service.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StartupLogger implements CommandLineRunner {

    private final Environment env;
    private final KnowledgeBase kb;
    private final CacheService cache;

    @Override
    public void run(String... args) {
        String port = env.getProperty("server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "");
        String url = "http://localhost:" + port + contextPath;

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║                                                          ║");
        System.out.println("║   🎤  СТЕНДАП-НАВИГАТОР ЗАПУЩЕН                         ║");
        System.out.println("║                                                          ║");
        System.out.println("║   🌐  Сайт:      " + padRight(url, 40) + "║");
        System.out.println("║   📚  Комиков:   " + padRight(String.valueOf(kb.getComedians().size()), 40) + "║");
        System.out.println("║   🔍  Поиск:     " + padRight(url + "/api/search?q=Воля", 40) + "║");
        System.out.println("║   💾  Кэш:       " + padRight("активен (TTL 5 мин)", 40) + "║");
        System.out.println("║   📡  Новости:   " + padRight("Google News (реальное время)", 40) + "║");
        System.out.println("║   🎥  Видео:     " + padRight("YouTube (реальное время)", 40) + "║");
        System.out.println("║   📅  Афиша:     " + padRight("Яндекс.Афиша + генерация", 40) + "║");
        System.out.println("║                                                          ║");
        System.out.println("║   Нажми Ctrl+C чтобы остановить                         ║");
        System.out.println("║                                                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private String padRight(String s, int n) {
        if (s == null) s = "";
        if (s.length() >= n) return s;
        return s + " ".repeat(n - s.length());
    }
}