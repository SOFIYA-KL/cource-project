package com.standupwiki.standup_wiki.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.standupwiki.standup_wiki.model.Comedian;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Ищет фото комика в русской Википедии (бесплатно, без ключа).
 * Берём фото только если в описании статьи есть «комик/стендап/юморист/КВН»,
 * чтобы не подставить однофамильца. Нет фото — на сайте останутся инициалы.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoService {

    private static final Pattern RELEVANT = Pattern.compile("комик|стендап|юморист|кввн|comedy|шоумен");

    private final CacheService cache;
    private final Semaphore slots = new Semaphore(4);   // не больше 4 запросов к Википедии одновременно
    private final KnowledgeBase kb;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Возвращает URL фото или пустую строку. Работает только для имён из нашей базы. */
    public String find(String name) {
        if (name == null || name.isBlank() || name.length() > 80) return "";

        Comedian c = kb.getComedians().stream()
                .filter(x -> name.equalsIgnoreCase(x.getName()))
                .findFirst().orElse(null);
        if (c == null) return "";

        String key = "photo:" + name.toLowerCase();
        String cached = cache.get(key, String.class);
        if (cached != null) return cached;

        String url = "";
        boolean failed = false;
        for (String title : new LinkedHashSet<>(Arrays.asList(c.getName(), c.getFullName()))) {
            if (title == null || title.isBlank()) continue;
            String r = fromWikipedia(title);
            if (r == null) failed = true;                 // сбой сети: повторим при следующем показе
            else if (!r.isEmpty()) { url = r; break; }
        }

        if (!url.isEmpty()) cache.put(key, url, 24 * 60);   // нашли: помним сутки
        else if (!failed) cache.put(key, "", 6 * 60);       // фото точно нет: проверим через 6 часов
        return url;
    }

    /** Ссылка на фото; "" если фото точно нет; null если сбой сети (повторим позже). */
    private String fromWikipedia(String title) {
        String t = URLEncoder.encode(title.trim().replace(' ', '_'), StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("https://ru.wikipedia.org/api/rest_v1/page/summary/" + t))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "StandupWiki/1.0 (student project)")
                .header("Accept", "application/json")
                .GET().build();

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                slots.acquire();
                try {
                    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    int code = resp.statusCode();
                    if (code == 404) return "";
                    if (code != 200) { Thread.sleep(400); continue; }

                    JsonNode j = mapper.readTree(resp.body());
                    if (!"standard".equals(j.path("type").asText())) return "";
                    String text = (j.path("description").asText("") + " " + j.path("extract").asText("")).toLowerCase();
                    if (!RELEVANT.matcher(text).find()) return "";
                    return j.path("thumbnail").path("source").asText("");
                } finally {
                    slots.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.debug("Wikipedia '{}': {}", title, e.getMessage());
            }
        }
        return null;
    }
}
