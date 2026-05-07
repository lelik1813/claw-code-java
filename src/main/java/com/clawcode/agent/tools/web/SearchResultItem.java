package com.clawcode.agent.tools.web;

public record SearchResultItem(
    String title,
    String url,
    String snippet,
    String source
) {}
