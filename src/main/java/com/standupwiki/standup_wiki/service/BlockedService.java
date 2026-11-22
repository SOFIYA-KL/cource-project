package com.standupwiki.standup_wiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BlockedService {

    private final ObjectMapper mapper = new ObjectMapper();
    private List<String> blocked = new ArrayList<>();

    @PostConstruct
    public void load() {
        try (var is = new ClassPathResource("data/blocked.txt").getInputStream();
             var r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            blocked = r.lines().map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .map(this::norm).collect(Collectors.toList());
            log.info("Чёрный список: {} имён", blocked.size());
        } catch (Exception e) {
            log.warn("blocked.txt не найден, фильтр пустой: {}", e.getMessage());
        }
    }

    private String norm(String s) {
        return s.toLowerCase().replace('ё', 'е');
    }

    /** Проверяет любой объект (комик, новость, видео, строку): ищет имя в его JSON. */
    public boolean isBlocked(Object item) {
        if (blocked.isEmpty() || item == null) return false;
        try {
            String json = norm(mapper.writeValueAsString(item));
            return blocked.stream().anyMatch(json::contains);
        } catch (Exception e) {
            return false;
        }
    }

    public <T> List<T> filter(List<T> items) {
        if (items == null) return new ArrayList<>();
        return items.stream().filter(i -> !isBlocked(i)).collect(Collectors.toList());
    }
}
