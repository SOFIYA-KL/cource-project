package com.standupwiki.standup_wiki.service;

import com.standupwiki.standup_wiki.model.Comedian;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutocorrectService {

    private final KnowledgeBase kb;

    /** Все известные слова: имена, города, проекты, ключевые слова, служебные слова поиска. */
    private Set<String> dictionary = new HashSet<>();
    /** Из них только кириллические: к ним «подтягиваем» опечатки. */
    private List<String> cyrDictionary = new ArrayList<>();

    // Результат анализа
    public static class QueryAnalysis {
        public String original;          // исходный запрос
        public String cleaned;           // очищенный и исправленный (по нему ищем)
        public boolean isMat;            // только мат
        public boolean hasMat;           // содержит мат
        public boolean isEmpty;          // пустой после очистки
        public boolean wasCorrected;     // исправили опечатки

        public QueryAnalysis(String original) {
            this.original = original;
        }
    }

    // Матерные слова
    private static final Set<String> MAT_WORDS = new HashSet<>(Arrays.asList(
            "хуй", "хуйня", "хуё", "хуе", "хуи", "хуя", "хую", "хуев", "хуеплет",
            "пизд", "пизда", "пиздец", "пиздить", "пиздюк",
            "бля", "блят", "блять", "блядь", "блядство",
            "ебат", "ебал", "еба", "ёба", "ебет", "ёбет", "ебут", "ёбут",
            "ебан", "ёбан", "ебаный", "ёбаный", "ебало", "ебуч", "ебля",
            "сука", "суки", "сучара", "сукин",
            "нахуй", "нахуя", "заебал", "заёб", "заеба",
            "пидор", "пидар", "пидорас", "пидр", "пидормот",
            "мудак", "мудила", "мудень", "мудозвон", "мудло",
            "говно", "говн", "говёный",
            "жопа", "жопу", "жоп", "жопошник",
            "срать", "срал", "сру", "срака",
            "дроч", "дрочит", "дрочун", "дрочила",
            "шлюха", "шлюх", "шлюшка",
            "проститут", "проститк",
            "уебок", "уёбок", "уебан", "уёбан", "уебак",
            "долбоеб", "долбоёб",
            "кретин", "дебил", "идиот",
            "манда", "мандавошка", "хер", "херня",
            "лох", "лошара", "лошок",
            "гондон", "гандон",
            "шмара", "упырь", "мразь", "тварь"
    ));

    @PostConstruct
    public void init() {
        Set<String> dict = new HashSet<>();
        for (Comedian c : kb.getComedians()) {
            addWords(dict, c.getName());
            addWords(dict, c.getFullName());
            addWords(dict, c.getBirthPlace());
            addWords(dict, c.getProject());
            if (c.getKeywords() != null) c.getKeywords().forEach(k -> addWords(dict, k));
        }
        SearchService.CATEGORY_WORDS.forEach(w -> addWords(dict, w));
        SearchService.TOP_WORDS.forEach(w -> addWords(dict, w));
        SearchService.DEFINITION_WORDS.forEach(w -> addWords(dict, w));

        dictionary = dict;
        cyrDictionary = dict.stream().filter(w -> w.matches("[а-яё]+")).sorted().toList();
        log.info("Словарь автокоррекции: {} слов", dictionary.size());
    }

    private void addWords(Set<String> set, String text) {
        if (text == null) return;
        for (String w : text.toLowerCase().split("[^а-яёa-z0-9]+")) {
            if (w.length() >= 3) set.add(w);
        }
    }

    /**
     * Главный метод: чистит запрос, убирает мат, исправляет опечатки.
     */
    public QueryAnalysis analyze(String query) {
        QueryAnalysis result = new QueryAnalysis(query);

        if (query == null || query.isBlank()) {
            result.isEmpty = true;
            return result;
        }

        // 1. Убираем знаки и лишние пробелы (цифры оставляем: «топ 10»)
        String base = query.toLowerCase().trim()
                .replaceAll("[!?.,;:@#$%^&*()\\[\\]{}<>\"'«»/|\\\\~`+=_\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (base.isEmpty()) {
            result.isEmpty = true;
            return result;
        }

        // 2. Слово за словом: повторы букв, раскладка, мат, опечатки
        List<String> words = new ArrayList<>();
        boolean changed = false;

        for (String w : base.split(" ")) {
            String word = fixLatinLookalikes(removeDuplicateLetters(w));

            if (isMatWord(word)) {
                result.hasMat = true;
                continue;
            }
            if (word.length() < 2 && !word.matches("\\d")) continue;

            String fixed = correctWord(word);
            if (!fixed.equals(w)) changed = true;
            words.add(fixed);
        }

        result.cleaned = String.join(" ", words);
        result.wasCorrected = changed;

        if (words.isEmpty()) {
            if (result.hasMat) {
                result.isMat = true;
                log.info("Запрос только мат: '{}'", query);
            } else {
                result.isEmpty = true;
            }
        }
        return result;
    }

    /**
     * «коммик» → «комик», «стэндапер» → «стендапер».
     * Исправляем только неизвестные слова и только на слово из словаря.
     */
    public String correctWord(String word) {
        if (word.length() < 4 || !word.matches("[а-яё]+") || dictionary.contains(word)) return word;

        int limit = word.length() >= 7 ? 2 : 1;
        String best = word;
        int bestDist = limit + 1;

        for (String d : cyrDictionary) {
            if (Math.abs(d.length() - word.length()) > limit) continue;
            int dist = levenshtein(word, d);
            if (dist < bestDist) {
                bestDist = dist;
                best = d;
            }
        }
        return best;
    }

    /**
     * Три и больше одинаковых буквы подряд сжимаем до двух: «воляяя» → «воляя».
     * Две оставляем, потому что они бывают в словах («Комиссаренко»).
     */
    public String removeDuplicateLetters(String input) {
        if (input == null || input.isEmpty()) return input;
        StringBuilder result = new StringBuilder();
        char prev = 0;
        int count = 0;
        for (char c : input.toCharArray()) {
            if (c == prev) {
                count++;
                if (count <= 1) result.append(c);
            } else {
                count = 0;
                result.append(c);
            }
            prev = c;
        }
        return result.toString();
    }

    /**
     * Латинские буквы-двойники меняем на русские только в словах со смешанной раскладкой.
     * «stand up» не трогаем, а «хуy» превратится в кириллицу.
     */
    public String fixLatinLookalikes(String input) {
        if (input == null) return null;
        boolean mixed = input.matches(".*[а-яё].*") && input.matches(".*[a-z].*");
        if (!mixed) return input;
        return input
                .replace('a', 'а').replace('e', 'е').replace('o', 'о').replace('p', 'р')
                .replace('c', 'с').replace('x', 'х').replace('y', 'у').replace('k', 'к')
                .replace('m', 'м').replace('t', 'т').replace('h', 'н').replace('b', 'в');
    }

    /**
     * Мат: точное совпадение или слово начинается с матерного корня (корень от 4 букв).
     * Слова из базы (фамилии, города) никогда не блокируются.
     */
    public boolean isMatWord(String word) {
        if (word == null || word.isEmpty()) return false;
        if (dictionary.contains(word)) return false;
        if (MAT_WORDS.contains(word)) return true;
        for (String mat : MAT_WORDS) {
            if (mat.length() >= 4 && word.startsWith(mat)) return true;
        }
        return false;
    }

    /**
     * Алгоритм Левенштейна.
     */
    public int levenshtein(String a, String b) {
        a = a.toLowerCase();
        b = b.toLowerCase();
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
