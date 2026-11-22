package com.standupwiki.standup_wiki.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.standupwiki.standup_wiki.model.Comedian;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Динамически расширяет базу комиков из русской Википедии.
 * Кандидаты берутся из трёх мест: категорий, поиска и файла data/names.txt (свои имена).
 * Данные о страницах запрашиваются пачками по 20 штук, вместе с описанием, биографией и фото.
 * Обновляется через 5 секунд после старта и затем раз в 6 часов.
 * В базу попадают только российские комики; статьи с упоминанием иноагента отбрасываются.
 * Это эвристика, а не гарантия: список нужно сверять с реестром Минюста (файл blocked.txt).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiCatalogService {

    private static final String[] CATEGORIES = {
            "Категория:Стендап-комики России",
            "Категория:Стендап-комики",
            "Категория:Комики России"
    };
    private static final String[] SEARCHES = {
            "российский стендап-комик",
            "российский комик стендап Stand Up ТНТ",
            "резидент Stand Up ТНТ комик",
            "участник шоу Открытый микрофон комик",
            "российский стендапер Comedy Club",
            "российский юморист стендап"
    };
    private static final Pattern FOREIGN_AGENT = Pattern.compile("иноагент|иностранн\\w* агент");
    private static final Pattern RELEVANT = Pattern.compile("комик|стендап|юморист");
    private static final String D = "(?:\\d{1,2}\\s+\\p{L}+\\s+\\d{3,4}|\\d{4})";
    private static final Pattern LIFE = Pattern.compile("(" + D + ")[^—–]*?[—–]\\s*(" + D + ")");
    private static final Pattern BORN = Pattern.compile("род\\.\\s*(" + D + ")");
    // Википедия требует понятный User-Agent с контактом. Впишите сюда свою почту.
    private static final String UA = "StandupWiki/1.0 (student course project; mailto:sofiaklucnik23@gmail.com)";
    private static final int MAX = 300;
    private static final int CHUNK = 20;

    private final KnowledgeBase kb;
    private final AutocorrectService autocorrect;
    private final BlockedService blocked;
    private final CacheService cache;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @PostConstruct
    public void start() {
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wiki-catalog");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(this::refresh, 5, 6 * 60 * 60, TimeUnit.SECONDS);
    }

    public void refresh() {
        try {
            Set<String> titles = new LinkedHashSet<>(seedNames());
            int seeds = titles.size();
            for (String category : CATEGORIES) {
                try {
                    titles.addAll(members(category));
                } catch (Exception e) {
                    log.warn("{}: {}", category, e.getMessage());
                }
            }
            for (String q : SEARCHES) {
                try {
                    titles.addAll(search(q));
                } catch (Exception e) {
                    log.warn("Поиск '{}': {}", q, e.getMessage());
                }
            }
            List<String> all = new ArrayList<>();
            for (String t : titles) {
                if (!t.startsWith("Список") && !t.contains(":")) all.add(t);
            }
            log.info("Википедия: кандидатов {} (своих имён из names.txt: {})", all.size(), seeds);

            Set<String> known = kb.baseNames();
            List<Comedian> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Map<String, Integer> rejected = new LinkedHashMap<>();

            for (int i = 0; i < all.size() && found.size() < MAX; i += CHUNK) {
                List<String> chunk = all.subList(i, Math.min(i + CHUNK, all.size()));
                JsonNode pages;
                try {
                    pages = pages(chunk);
                } catch (Exception e) {
                    log.warn("Пачка страниц не загружена: {}", e.getMessage());
                    reject(rejected, "ошибка запроса");
                    continue;
                }
                for (JsonNode page : pages) {
                    Comedian c = toComedian(page, rejected);
                    if (c == null) continue;
                    String key = c.getName().toLowerCase();
                    if (known.contains(key)) { reject(rejected, "уже есть в базе"); continue; }
                    if (!seen.add(key)) { reject(rejected, "дубль"); continue; }
                    if (blocked.isBlocked(c)) { reject(rejected, "в blocked.txt"); continue; }
                    found.add(c);
                    String thumb = page.path("thumbnail").path("source").asText("");
                    if (!thumb.isEmpty()) cache.put("photo:" + key, thumb, 24 * 60);   // фото без отдельного запроса
                }
                Thread.sleep(250);    // не нагружаем Википедию
            }

            kb.setExtra(found);
            autocorrect.init();    // новые имена в словарь автокоррекции
            log.info("Каталог из Википедии: добавлено {} комиков. Отсеяно: {}", found.size(), rejected);
        } catch (Exception e) {
            log.warn("Каталог из Википедии не обновлён: {}", e.getMessage());
        }
    }

    private void reject(Map<String, Integer> rejected, String reason) {
        rejected.merge(reason, 1, Integer::sum);
    }

    /** Свои имена: data/names.txt, по одному в строке (названия статей Википедии). */
    private List<String> seedNames() {
        List<String> out = new ArrayList<>();
        try (var is = new ClassPathResource("data/names.txt").getInputStream();
             var r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            r.lines().map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#")).forEach(out::add);
        } catch (Exception e) {
            log.debug("names.txt не найден: {}", e.getMessage());
        }
        return out;
    }

    private List<String> members(String category) throws Exception {
        String url = "https://ru.wikipedia.org/w/api.php?action=query&list=categorymembers&cmtype=page&cmlimit=200&format=json"
                + "&cmtitle=" + URLEncoder.encode(category, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (JsonNode m : mapper.readTree(get(url)).path("query").path("categorymembers")) {
            out.add(m.path("title").asText());
        }
        return out;
    }

    private List<String> search(String query) throws Exception {
        List<String> out = new ArrayList<>();
        for (int offset = 0; offset < 150; offset += 50) {
            String url = "https://ru.wikipedia.org/w/api.php?action=query&list=search&srnamespace=0&srlimit=50&format=json"
                    + "&sroffset=" + offset + "&srsearch=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            for (JsonNode m : mapper.readTree(get(url)).path("query").path("search")) {
                out.add(m.path("title").asText());
            }
        }
        return out;
    }

    /** Одним запросом: описание, начало статьи и миниатюра фото для 20 страниц. */
    private JsonNode pages(List<String> titles) throws Exception {
        String url = "https://ru.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1"
                + "&prop=extracts%7Cdescription%7Cpageimages&exintro=1&explaintext=1&exlimit=20"
                + "&piprop=thumbnail&pithumbsize=500&titles="
                + URLEncoder.encode(String.join("|", titles), StandardCharsets.UTF_8);
        return mapper.readTree(get(url)).path("query").path("pages");
    }

    private Comedian toComedian(JsonNode page, Map<String, Integer> rejected) {
        if (page.path("missing").asBoolean(false)) { reject(rejected, "нет статьи"); return null; }

        String title = page.path("title").asText("");
        String description = page.path("description").asText("");
        String extract = page.path("extract").asText("");
        String text = (description + " " + extract).toLowerCase();

        if (title.isEmpty()) return null;
        if (FOREIGN_AGENT.matcher(text).find()) { reject(rejected, "упомянут статус иноагента"); return null; }
        if (!RELEVANT.matcher(text).find()) { reject(rejected, "не комик"); return null; }
        if (!text.contains("россий")) { reject(rejected, "не российский"); return null; }

        String name = title.replaceAll("\\s*\\(.*?\\)", "").trim();
        Comedian c = new Comedian();
        c.setName(name);
        c.setFullName(title);
        StringBuilder ini = new StringBuilder();
        for (String w : name.split("\\s+")) {
            if (!w.isEmpty() && ini.length() < 2) ini.append(Character.toUpperCase(w.charAt(0)));
        }
        c.setPhoto(ini.toString());
        if (!description.isEmpty()) {
            c.setStyle(Character.toUpperCase(description.charAt(0)) + description.substring(1));
        }
        c.setBio(extract);
        String[] life = lifespan(extract);
        if (life[0] != null) c.setBirthDate(life[0]);
        if (life[1] != null) c.setDeathDate(life[1]);
        c.setKeywords(new ArrayList<>(Arrays.asList(name.toLowerCase().split("\\s+"))));
        return c;
    }

    /** Годы жизни из первых скобок статьи: «(15 августа 1950, Москва — 3 марта 2019)» или «(род. 14 февраля 1979)». */
    private String[] lifespan(String extract) {
        String t = extract.replace("\u0301", "").replace('\u00a0', ' ');
        int a = t.indexOf('(');
        if (a < 0 || a > 300) return new String[2];
        int depth = 0, b = -1;
        for (int i = a; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) { b = i; break; }
        }
        if (b < 0) return new String[2];
        String in = t.substring(a + 1, b);
        Matcher m = LIFE.matcher(in);
        if (m.find()) return new String[]{m.group(1), m.group(2)};
        Matcher r = BORN.matcher(in);
        return new String[]{r.find() ? r.group(1) : null, null};
    }

    /** Запрос к Википедии: понятный User-Agent, пауза между запросами и повтор при HTTP 429. */
    private String get(String url) throws Exception {
        for (int attempt = 1; ; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", UA)
                    .header("Api-User-Agent", UA)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
            if (code == 200) {
                Thread.sleep(700);   // не чаще ~1 запроса в секунду
                return resp.body();
            }
            if ((code == 429 || code == 503) && attempt <= 4) {
                long wait = resp.headers().firstValueAsLong("Retry-After").orElse(5L * attempt);
                log.info("Википедия просит подождать (HTTP {}), пауза {} с, попытка {}", code, Math.min(wait, 60), attempt);
                Thread.sleep(Math.min(wait, 60) * 1000);
                continue;
            }
            throw new java.io.IOException("HTTP " + code);
        }
    }
}
