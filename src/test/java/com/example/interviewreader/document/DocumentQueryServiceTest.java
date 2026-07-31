package com.example.interviewreader.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentQueryServiceTest {

    @Test
    void centersLongSearchSnippetsAroundTheMatch() {
        var prefix = "前置内容".repeat(40);
        var suffix = "后置内容".repeat(40);

        var snippet = DocumentQueryService.centeredSnippet(prefix + "HashMap" + suffix, "hashmap");

        assertThat(snippet)
                .startsWith("…")
                .endsWith("…")
                .contains("HashMap")
                .hasSizeLessThanOrEqualTo(142);
    }

    @Test
    void fallsBackToTheBeginningWhenOnlyTheTitleMatched() {
        var text = "正文内容".repeat(50);

        var snippet = DocumentQueryService.centeredSnippet(text, "标题命中");

        assertThat(snippet)
                .startsWith("正文内容")
                .endsWith("…")
                .hasSizeLessThanOrEqualTo(141);
    }
}
