package com.standupwiki.standup_wiki.parser;

import com.standupwiki.standup_wiki.service.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Свежие новости из RSS-поиска Bing News (с картинками) и Google News (запасной).
 * Заглушек нет: если ничего не нашлось, возвращается пустой список.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsParser {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final int LIMIT = 30;
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter OUT =
            DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru"));

    private final CacheService cache;

    private record Item(Map<String, String> data, long ts) {}

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getNews(String query) {
        String key = "news:" + query.toLowerCase();
        List<Map<String, String>> cached = cache.get(key, List.class);
        if (cached != null) return cached;

        // «Щербаков, Алексей Сергеевич» (название статьи Википедии) -> «Алексей Щербаков»
        final String name = normalizeName(query);
        boolean person = name.split("\\s+").length >= 2 && !name.toLowerCase().contains("стендап");
        List<Item> items = new ArrayList<>();
        String[] windows = {"30d", "1825d"};   // немного запросов: на всю выдачу даётся 8 секунд

        if (person) {
            // 1) имя в кавычках: точные совпадения, без лишнего слова «стендап»
            String exact = "\"" + name + "\"";
            for (String when : windows) {
                items.addAll(google(exact, when));
                if (items.size() >= 12) break;
            }
            items.addAll(bing(exact));
            // 2) если мало, добавляем запрос «имя + стендап»
            if (items.size() < 12) {
                items.addAll(google(name + " стендап", "1825d"));
                items.addAll(bing(name + " стендап"));
            }
        } else {
            String q = name.toLowerCase().contains("стендап") ? name : name + " стендап";
            for (String when : new String[]{"2d", "7d", "30d", "365d"}) {
                items.addAll(google(q, when));
                if (items.size() >= 12) break;
            }
            items.addAll(bing(q));
        }

        items.sort((x, y) -> Long.compare(y.ts(), x.ts()));   // самые свежие сверху

        // Для людей оставляем только новости, где встречается фамилия (без учёта падежа)
        String stem = "";
        if (person) {
            String[] w = name.toLowerCase().trim().split("\\s+");
            String last = w[w.length - 1];
            stem = last.substring(0, Math.max(4, last.length() - 2));
        }

        List<Map<String, String>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item it : items) {
            if (result.size() >= LIMIT) break;
            String title = it.data().get("title").toLowerCase();
            if (person && !title.contains(stem)) continue;
            if (seen.add(title)) result.add(it.data());
        }

        if (person && result.isEmpty()) {      // фильтр по фамилии ничего не оставил: показываем то, что нашёл точный поиск
            for (Item it : items) {
                if (result.size() >= LIMIT) break;
                if (seen.add(it.data().get("title").toLowerCase() + "#")) result.add(it.data());
            }
        }

        cache.put(key, result, 10);
        return result;
    }

    private String normalizeName(String q) {
        String s = q == null ? "" : q.trim();
        int c = s.indexOf(',');
        if (c > 0) {
            String surname = s.substring(0, c).trim();
            String[] given = s.substring(c + 1).trim().split("\\s+");
            if (given.length > 0 && !given[0].isEmpty()) return given[0] + " " + surname;
        }
        return s;
    }

    // ---------- Bing News RSS (есть картинки) ----------
    private List<Item> bing(String q) {
        List<Item> out = new ArrayList<>();
        try {
            Document doc = fetchXml("https://www.bing.com/news/search?q=" + enc(q)
                    + "&format=rss&setlang=ru&cc=RU&qft=sortbydate%3d%221%22");
            for (Element e : doc.select("item")) {
                String title = text(e, "title");
                String link = realUrl(text(e, "link"));
                if (title.isEmpty() || link.isEmpty()) continue;

                String pub = text(e, "pubDate");
                Map<String, String> m = new LinkedHashMap<>();
                m.put("title", title);
                m.put("link", link);
                m.put("source", orElse(text(e, "News:Source"), host(link)));
                m.put("image", fixImage(text(e, "News:Image")));
                m.put("time", fmt(pub));
                m.put("ts", String.valueOf(ts(pub)));
                out.add(new Item(m, ts(pub)));
            }
        } catch (Exception ex) {
            log.warn("Bing News: {}", ex.getMessage());
        }
        return out;
    }

    // ---------- Google News RSS (без картинок) ----------
    private List<Item> google(String q, String when) {
        List<Item> out = new ArrayList<>();
        try {
            Document doc = fetchXml("https://news.google.com/rss/search?q=" + enc(q + " when:" + when)
                    + "&hl=ru&gl=RU&ceid=RU:ru");
            for (Element e : doc.select("item")) {
                String title = text(e, "title");
                String src = text(e, "source");
                String link = text(e, "link");
                if (title.isEmpty() || link.isEmpty()) continue;

                // Google добавляет в конец заголовка « - Источник»
                if (!src.isEmpty() && title.endsWith(" - " + src)) {
                    title = title.substring(0, title.length() - src.length() - 3);
                }

                String pub = text(e, "pubDate");
                Map<String, String> m = new LinkedHashMap<>();
                m.put("title", title);
                m.put("link", link);
                m.put("source", orElse(src, "Google Новости"));
                m.put("image", "");
                m.put("time", fmt(pub));
                m.put("ts", String.valueOf(ts(pub)));
                out.add(new Item(m, ts(pub)));
            }
        } catch (Exception ex) {
            log.warn("Google News: {}", ex.getMessage());
        }
        return out;
    }

    // ---------- вспомогательное ----------
    private Document fetchXml(String url) throws Exception {
        return Jsoup.connect(url)
                .userAgent(UA)
                .timeout(6000)
                .ignoreContentType(true)
                .parser(Parser.xmlParser())     // важно: в HTML-режиме <link> теряет текст
                .get();
    }

    private String text(Element item, String tag) {
        Element t = item.getElementsByTag(tag).first();
        return t == null ? "" : t.text().trim();
    }

    private String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String orElse(String a, String b) {
        return a == null || a.isEmpty() ? b : a;
    }

    /** Bing отдаёт ссылку-редирект, настоящий адрес лежит в параметре url=... */
    private String realUrl(String link) {
        if (link.contains("apiclick")) {
            int i = link.indexOf("url=");
            if (i >= 0) {
                String u = link.substring(i + 4);
                int amp = u.indexOf('&');
                if (amp >= 0) u = u.substring(0, amp);
                try {
                    return URLDecoder.decode(u, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
            }
        }
        return link;
    }

    private String fixImage(String img) {
        if (img == null || img.isBlank()) return "";
        if (img.startsWith("//")) return "https:" + img;
        if (img.startsWith("/")) return "https://www.bing.com" + img;
        return img.replaceFirst("^http://", "https://");
    }

    private String host(String link) {
        try {
            String h = new URI(link).getHost();
            return h == null ? "Новости" : h.replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return "Новости";
        }
    }

    private long ts(String pub) {
        try {
            return ZonedDateTime.parse(pub, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
        } catch (Exception e) {
            return 0;
        }
    }

    private String fmt(String pub) {
        try {
            return ZonedDateTime.parse(pub, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(MSK).format(OUT);
        } catch (Exception e) {
            return pub == null ? "" : pub;
        }
    }
}
