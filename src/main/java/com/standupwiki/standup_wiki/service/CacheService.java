package com.standupwiki.standup_wiki.service;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Service
public class CacheService {

    // Время жизни по умолчанию — 5 минут
    private static final long TTL_MINUTES = 5;

    private final Map<String, CacheEntry<?>> cache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        CacheEntry<?> entry = cache.get(key);
        if (entry == null) return null;

        if (entry.getExpiresAt().isBefore(LocalDateTime.now())) {
            cache.remove(key);
            return null;
        }

        return (T) entry.getValue();
    }

    public <T> void put(String key, T value) {
        put(key, value, TTL_MINUTES);
    }

    /** Положить в кэш на своё время (фото живут сутки, новости десять минут). */
    public <T> void put(String key, T value, long ttlMinutes) {
        CacheEntry<T> entry = new CacheEntry<>();
        entry.setValue(value);
        entry.setExpiresAt(LocalDateTime.now().plusMinutes(ttlMinutes));
        cache.put(key, entry);
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    @Data
    public static class CacheEntry<T> {
        private T value;
        private LocalDateTime expiresAt;
    }
}
