package com.standupwiki.standup_wiki.model;

import lombok.Data;
import java.util.List;

@Data
public class Comedian {
    private String name;
    private String fullName;
    private String photo;
    private String birthDate;
    private String deathDate;
    private String birthPlace;
    private String project;
    private String style;
    private String bio;
    private String fact;
    private List<String> keywords;
    private String videoUrl;
    private String newsText;
    private String eventInfo;
}