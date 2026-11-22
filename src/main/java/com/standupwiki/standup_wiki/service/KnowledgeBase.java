package com.standupwiki.standup_wiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.standupwiki.standup_wiki.model.Comedian;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * База комиков: статическая часть из comedians.json
 * плюс динамическая из Википедии (её подгружает WikiCatalogService).
 */
@Service
public class KnowledgeBase {

    private List<Comedian> comedians = new ArrayList<>();           // из comedians.json
    private volatile List<Comedian> extra = new ArrayList<>();      // из Википедии

    @PostConstruct
    public void load() {
        try (InputStream is = new ClassPathResource("data/comedians.json").getInputStream()) {
            ComediansWrapper wrapper = new ObjectMapper().readValue(is, ComediansWrapper.class);
            this.comedians = wrapper.getComedians();
            System.out.println("📚 База знаний загружена: " + comedians.size() + " комиков");
        } catch (Exception e) {
            System.err.println("❌ Ошибка загрузки: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Все комики: сначала из json (они выше в «топе»), потом из Википедии. */
    public List<Comedian> getComedians() {
        List<Comedian> all = new ArrayList<>(comedians);
        all.addAll(extra);
        return all;
    }

    /** Имена из comedians.json в нижнем регистре, чтобы не дублировать их из Википедии. */
    public Set<String> baseNames() {
        Set<String> names = new HashSet<>();
        for (Comedian c : comedians) {
            if (c.getName() != null) names.add(c.getName().toLowerCase());
        }
        return names;
    }

    public int extraCount() {
        return extra.size();
    }

    public void setExtra(List<Comedian> list) {
        this.extra = list;
    }

    @Data
    static class ComediansWrapper {
        private List<Comedian> comedians;
    }
}
