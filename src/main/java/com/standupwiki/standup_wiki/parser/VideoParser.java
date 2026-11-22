package com.standupwiki.standup_wiki.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.standupwiki.standup_wiki.service.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Реальные видео с YouTube по имени комика.
 * 1) Если в application.properties есть youtube.api.key, используем официальный API.
 * 2) Иначе читаем страницу поиска YouTube (работает без ключа, но может сломаться при смене вёрстки).
 * 3) Если и это не вышло, показываем видео из comedians.json.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoParser {

    private static final Pattern YT_ID = Pattern.compile("(?:youtu\\.be/|v=|embed/)([\\w-]{11})");
    private static final String DATA_MARK = "var ytInitialData = ";
    private static final int LIMIT = 15;

    private final CacheService cache;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${youtube.api.key:}")
    private String apiKey;

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getVideos(String name, String fallbackUrl) {
        String key = "videos:" + name.toLowerCase();
        List<Map<String, String>> cached = cache.get(key, List.class);
        if (cached != null) return cached;

        String q = name.toLowerCase().contains("стендап") ? name : name + " стендап";

        List<Map<String, String>> videos = new ArrayList<>();
        if (apiKey != null && !apiKey.isBlank()) videos = viaApi(q);
        if (videos.isEmpty()) videos = viaPage(q);

        cache.put(key, videos, 30);
        return videos;
    }

    // ---------- 1. Официальный API ----------
    private List<Map<String, String>> viaApi(String q) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            String url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video"
                    + "&maxResults=" + LIMIT + "&regionCode=RU&relevanceLanguage=ru"
                    + "&q=" + enc(q) + "&key=" + enc(apiKey);
            JsonNode root = mapper.readTree(get(url));
            for (JsonNode it : root.path("items")) {
                String id = it.path("id").path("videoId").asText("");
                if (id.isEmpty()) continue;
                JsonNode sn = it.path("snippet");
                out.add(video(id,
                        Jsoup.parse(sn.path("title").asText("")).text(),   // убираем &quot; и т.п.
                        sn.path("channelTitle").asText("YouTube"),
                        sn.path("publishedAt").asText("").split("T")[0]));
            }
        } catch (Exception e) {
            log.warn("YouTube API: {}", e.getMessage());
        }
        return out;
    }

    // ---------- 2. Страница поиска без ключа ----------
    private List<Map<String, String>> viaPage(String q) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            String html = get("https://www.youtube.com/results?hl=ru&gl=RU&search_query=" + enc(q));
            int i = html.indexOf(DATA_MARK);
            if (i < 0) {
                log.warn("YouTube: в ответе нет данных поиска (страница согласия или блокировка), длина ответа {}", html.length());
                return out;
            }

            // readTree читает первый JSON-объект и игнорирует хвост страницы
            JsonNode root = mapper.readTree(html.substring(i + DATA_MARK.length()));
            List<JsonNode> found = new ArrayList<>();
            collect(root, found);

            Set<String> seen = new HashSet<>();
            for (JsonNode v : found) {
                String id = v.path("videoId").asText("");
                if (id.isEmpty() || !seen.add(id)) continue;
                out.add(video(id,
                        v.path("title").path("runs").path(0).path("text").asText(""),
                        v.path("ownerText").path("runs").path(0).path("text").asText("YouTube"),
                        v.path("publishedTimeText").path("simpleText").asText("")));
                if (out.size() >= LIMIT) break;
            }
        } catch (Exception e) {
            log.warn("YouTube (страница): {}", e.getMessage());
        }
        return out;
    }

    private void collect(JsonNode n, List<JsonNode> out) {
        if (n == null) return;
        if (n.isObject()) {
            JsonNode v = n.get("videoRenderer");
            if (v != null) out.add(v);
            n.fields().forEachRemaining(e -> collect(e.getValue(), out));
        } else if (n.isArray()) {
            n.forEach(c -> collect(c, out));
        }
    }

    // ---------- 3. Видео из базы ----------
    private List<Map<String, String>> fromUrl(String url, String name) {
        List<Map<String, String>> out = new ArrayList<>();
        if (url == null) return out;
        Matcher m = YT_ID.matcher(url);
        if (m.find()) out.add(video(m.group(1), "Видео: " + name, "YouTube", ""));
        return out;
    }

    // ---------- вспомогательное ----------
    private Map<String, String> video(String id, String title, String channel, String time) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("videoId", id);
        v.put("title", title);
        v.put("url", "https://www.youtube.com/watch?v=" + id);      // сразу на видео, не на поиск
        v.put("thumbnail", "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg");
        v.put("source", channel);
        v.put("time", time);
        return v;
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .header("Cookie", "CONSENT=YES+1")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
