package com.standupwiki.standup_wiki.service;

import com.standupwiki.standup_wiki.model.Comedian;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final KnowledgeBase kb;
    private final AutocorrectService autocorrect;

    // Категории запросов (package-private: из них AutocorrectService строит словарь)
    static final Set<String> CATEGORY_WORDS = new HashSet<>(Arrays.asList(
            "комик", "комики", "комиков", "комика", "стендап", "стендапер", "стендаперы", "стендаперов",
            "standup", "юмор", "юморист", "юмористы", "шуты",
            "все", "всё", "список", "каталог"
    ));

    // Запросы «топ»
    static final Set<String> TOP_WORDS = new HashSet<>(Arrays.asList(
            "топ", "лучшие", "популярные", "известные", "знаменитые"
    ));

    // «Что такое стендап», «кто такой комик»
    static final Set<String> DEFINITION_WORDS = new HashSet<>(Arrays.asList(
            "что", "кто", "такое", "такой", "такая", "значит", "определение"
    ));

    private static final String DEFINITION =
            "Стендап (stand-up comedy) — жанр разговорной комедии: комик выходит на сцену один "
                    + "и рассказывает монолог из шуток и историй от первого лица. "
                    + "Комик, или стендапер, — тот, кто выступает в этом жанре.";

    private static final Pattern TOP_NUM = Pattern.compile("^(?:топ)?(\\d{1,3})$");

    public enum ResultType {
        EXACT,        // точное совпадение комика
        SUGGESTIONS,  // есть подсказки «возможно, вы имели в виду»
        CATEGORY,     // категория (все комики / топ / определение)
        EMPTY,        // ничего не найдено
        INVALID       // мат / некорректный
    }

    public static class SearchResponse {
        public ResultType type;
        public String originalQuery;
        public String cleanedQuery;
        public boolean wasCorrected;
        public List<Comedian> mainResults = new ArrayList<>();
        public List<String> suggestions = new ArrayList<>();
        public String categoryInfo;
        public long timeMs;
    }

    public SearchResponse search(String query) {
        long start = System.currentTimeMillis();
        SearchResponse response = new SearchResponse();
        response.originalQuery = query;

        // 1. Анализ и исправление запроса
        AutocorrectService.QueryAnalysis analysis = autocorrect.analyze(query);
        response.cleanedQuery = analysis.cleaned;
        response.wasCorrected = analysis.wasCorrected;

        // 2. Мусор / мат
        if (analysis.isMat || analysis.isEmpty) {
            response.type = ResultType.INVALID;
            response.timeMs = System.currentTimeMillis() - start;
            return response;
        }

        String cleaned = analysis.cleaned;
        String[] words = cleaned.split("\\s+");
        List<Comedian> all = allComedians();

        // 3. Категория
        if (isCategory(cleaned, words)) {
            response.type = ResultType.CATEGORY;

            if (isDefinitionQuery(words)) {
                response.categoryInfo = DEFINITION;
                response.mainResults = all.stream().limit(6).toList();
            } else if (isTopQuery(words)) {
                int n = Math.max(1, Math.min(extractNumber(words, 5), all.size()));
                response.mainResults = all.stream().limit(n).toList();
                response.categoryInfo = "Топ-" + n + " комиков";
            } else {
                response.mainResults = all;
                response.categoryInfo = "Все комики (" + all.size() + ")";
            }

            response.timeMs = System.currentTimeMillis() - start;
            return response;
        }

        // 4. Ищем комиков
        List<Match> matches = new ArrayList<>();
        for (Comedian c : all) {
            int score = scoreComedian(c, cleaned, words);
            if (score > 0) matches.add(new Match(c, score));
        }
        matches.sort((a, b) -> b.score - a.score);

        // 5. Тип результата
        if (matches.isEmpty()) {
            response.type = ResultType.EMPTY;
            response.timeMs = System.currentTimeMillis() - start;
            return response;
        }

        Match best = matches.get(0);

        if (best.score >= 80) {
            response.type = ResultType.EXACT;
            response.mainResults.add(best.comedian);
            for (int i = 1; i < matches.size() && i <= 3; i++) {
                Match m = matches.get(i);
                if (m.score >= 40) response.suggestions.add(m.comedian.getName());
            }
        } else if (best.score >= 40) {
            response.type = ResultType.SUGGESTIONS;
            response.mainResults.add(best.comedian);
            for (int i = 1; i < matches.size() && response.suggestions.size() < 4; i++) {
                response.suggestions.add(matches.get(i).comedian.getName());
            }
        } else {
            response.type = ResultType.SUGGESTIONS;
            for (int i = 0; i < matches.size() && response.suggestions.size() < 5; i++) {
                response.suggestions.add(matches.get(i).comedian.getName());
            }
        }

        response.timeMs = System.currentTimeMillis() - start;
        return response;
    }

    /** База без дублей по имени (в comedians.json «Иван Абрамов» записан дважды). */
    private List<Comedian> allComedians() {
        Map<String, Comedian> unique = new LinkedHashMap<>();
        for (Comedian c : kb.getComedians()) {
            if (c.getName() != null) unique.putIfAbsent(c.getName().toLowerCase(), c);
        }
        return new ArrayList<>(unique.values());
    }

    private boolean isTopWord(String w) {
        return TOP_WORDS.contains(w) || w.matches("топ\\d+");
    }

    private boolean isCategory(String cleaned, String[] words) {
        if (cleaned.contains("stand up")) return true;
        for (String w : words) {
            if (CATEGORY_WORDS.contains(w) || isTopWord(w)) return true;
        }
        return false;
    }

    private boolean isTopQuery(String[] words) {
        for (String w : words) {
            if (isTopWord(w)) return true;
        }
        return false;
    }

    private boolean isDefinitionQuery(String[] words) {
        for (String w : words) {
            if (DEFINITION_WORDS.contains(w)) return true;
        }
        return false;
    }

    /** «топ 10», «топ10», «10 лучших» → 10 */
    private int extractNumber(String[] words, int def) {
        for (String w : words) {
            Matcher m = TOP_NUM.matcher(w);
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        return def;
    }

    /**
     * Скоринг комика по запросу.
     */
    private int scoreComedian(Comedian c, String query, String[] words) {
        int score = 0;
        String name = c.getName().toLowerCase();
        String fullName = c.getFullName() != null ? c.getFullName().toLowerCase() : "";
        String project = c.getProject() != null ? c.getProject().toLowerCase() : "";
        String style = c.getStyle() != null ? c.getStyle().toLowerCase() : "";
        String bio = c.getBio() != null ? c.getBio().toLowerCase() : "";

        if (name.equals(query)) return 100;
        if (fullName.equals(query)) return 100;

        if (name.contains(query)) score += 60;
        if (query.contains(name)) score += 70;

        String[] nameParts = name.split("\\s+");
        for (String word : words) {
            if (word.length() < 2) continue;

            for (String part : nameParts) {
                if (part.equals(word)) score += 50;
                else if (part.contains(word) || word.contains(part)) score += 25;
                else {
                    int dist = autocorrect.levenshtein(word, part);
                    if (dist <= 1) score += 20;
                    else if (dist <= 2 && word.length() >= 4) score += 10;
                }
            }

            if (c.getKeywords() != null) {
                for (String kw : c.getKeywords()) {
                    String k = kw.toLowerCase();
                    if (k.equals(word)) score += 30;
                    else if (k.contains(word) || word.contains(k)) score += 15;
                    else {
                        int dist = autocorrect.levenshtein(word, k);
                        if (dist <= 1) score += 12;
                    }
                }
            }

            if (project.contains(word)) score += 10;
            if (style.contains(word)) score += 5;
            if (word.length() > 3 && bio.contains(word)) score += 3;
        }

        return score;
    }

    private static class Match {
        Comedian comedian;
        int score;

        Match(Comedian c, int s) {
            this.comedian = c;
            this.score = s;
        }
    }
}
