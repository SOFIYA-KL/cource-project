package com.standupwiki.standup_wiki.controller;

import com.standupwiki.standup_wiki.model.Comedian;
import com.standupwiki.standup_wiki.parser.EventParser;
import com.standupwiki.standup_wiki.parser.NewsParser;
import com.standupwiki.standup_wiki.parser.VideoParser;
import com.standupwiki.standup_wiki.service.BlockedService;
import com.standupwiki.standup_wiki.service.KnowledgeBase;
import com.standupwiki.standup_wiki.service.PhotoService;
import com.standupwiki.standup_wiki.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SearchController {

    private final SearchService searchService;
    private final NewsParser newsParser;
    private final VideoParser videoParser;
    private final EventParser eventParser;
    private final PhotoService photoService;
    private final BlockedService blocked;
    private final KnowledgeBase kb;

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q) {
        SearchService.SearchResponse s = searchService.search(q);
        SearchService.ResultType type = s.type;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", q);
        response.put("type", type.toString().toLowerCase());
        response.put("invalid", type == SearchService.ResultType.INVALID);
        response.put("cleanedQuery", s.cleanedQuery);
        response.put("corrected", s.cleanedQuery);
        response.put("wasCorrected", s.wasCorrected);
        response.put("categoryInfo", s.categoryInfo);
        response.put("suggestions", blocked.filter(s.suggestions));
        response.put("timeMs", s.timeMs);

        // Комики без иноагентов
        List<Comedian> main = blocked.filter(s.mainResults);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Comedian c : main) {
            results.add(Map.<String, Object>of("comedian", c));
        }
        response.put("results", results);
        response.put("count", results.size());

        List<Map<String, String>> news = new ArrayList<>();
        List<Map<String, String>> videos = new ArrayList<>();
        Object events = new ArrayList<>();

        if (type == SearchService.ResultType.CATEGORY) {
            // «топ 10 комиков», «что такое стендап»: показываем общие свежие новости жанра
            news = blocked.filter(newsParser.getNews("стендап"));

        } else if (!main.isEmpty()) {
            Comedian top = main.get(0);
            String name = top.getName();
            String term = s.cleanedQuery;
            boolean deceased = top.getDeathDate() != null && !top.getDeathDate().isBlank();   // афиша для умерших не нужна

            // Новости, видео и афиша грузятся параллельно, не дольше 8 секунд
            var newsF = CompletableFuture.supplyAsync(() -> newsParser.getNews(name))
                    .orTimeout(8, TimeUnit.SECONDS)
                    .exceptionally(ex -> List.<Map<String, String>>of());
            var videosF = CompletableFuture.supplyAsync(() -> videoParser.getVideos(name, top.getVideoUrl()))
                    .orTimeout(8, TimeUnit.SECONDS)
                    .exceptionally(ex -> List.<Map<String, String>>of());
            var eventsF = CompletableFuture.supplyAsync(() -> deceased ? null : eventParser.getEvents(term))
                    .orTimeout(8, TimeUnit.SECONDS)
                    .exceptionally(ex -> null);

            news = blocked.filter(newsF.join());
            videos = blocked.filter(videosF.join());
            events = eventsF.join();
        }

        response.put("news", news);
        response.put("videos", videos);
        response.put("events", events == null ? new ArrayList<>() : events);
        return response;
    }

    /** Фото комика (из Википедии). Сайт запрашивает его отдельно, чтобы поиск не тормозил. */
    @GetMapping("/photo")
    public Map<String, String> photo(@RequestParam String name) {
        return Map.of("url", photoService.find(name));
    }

    /** Свежие новости жанра для главной страницы. */
    @GetMapping("/latest")
    public List<Map<String, String>> latest() {
        return blocked.filter(newsParser.getNews("стендап")).stream().limit(6).toList();
    }

    /** Сколько комиков в базе (для счётчика на главной). */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        int total = (int) kb.getComedians().stream().map(c -> c.getName().toLowerCase()).distinct().count();
        return Map.of("total", total, "wiki", kb.extraCount());
    }

    /**
     * Проверка перед демонстрацией: открыть http://localhost:8080/api/diagnostics.
     * Показывает размер базы и работают ли внешние источники (-1 означает сбой).
     */
    @GetMapping("/diagnostics")
    public Map<String, Object> diagnostics() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("comedians_total", (int) kb.getComedians().stream().map(c -> c.getName().toLowerCase()).distinct().count());
        d.put("comedians_from_wikipedia", kb.extraCount());
        d.put("news_found", probe(() -> newsParser.getNews("стендап").size()));
        d.put("videos_found", probe(() -> videoParser.getVideos("Павел Воля", null).size()));
        d.put("photo_found", probe(() -> photoService.find("Павел Воля").isEmpty() ? 0 : 1));
        return d;
    }

    private int probe(java.util.function.Supplier<Integer> check) {
        try {
            return check.get();
        } catch (Exception e) {
            return -1;
        }
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
