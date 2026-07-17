package com.example.interviewreader.document;

import java.util.Set;

public final class ApiContractValues {
    public static final Set<String> SOURCE_TYPES = Set.of("PDF", "EXCEL", "JSON_PACKAGE", "MARKDOWN", "MANUAL");
    public static final Set<String> NODE_TYPES = Set.of("PART", "CHAPTER", "SECTION", "SUBSECTION", "QUESTION", "APPENDIX", "OTHER");
    public static final Set<String> BLOCK_TYPES = Set.of(
            "paragraph", "heading_note", "unordered_list", "ordered_list", "code", "table",
            "quote", "callout", "formula", "image", "divider", "table_snapshot");
    public static final Set<String> VERSION_STATUSES = Set.of("DRAFT", "PUBLISHED", "RETIRED");
    public static final Set<String> IMPORT_STATUSES = Set.of(
            "UPLOADED", "PREFLIGHT", "EXTRACTING", "NORMALIZING", "VALIDATING",
            "READY", "REVIEW_REQUIRED", "IMPORTED", "FAILED", "CANCELED");
    public static final Set<String> IMPORT_STAGES = Set.of(
            "UPLOADED", "PREFLIGHT", "EXTRACTING", "NORMALIZING", "VALIDATING",
            "REVIEWING", "FAILED", "CANCELED", "COMMITTED", "DRAFT_DISCARDED");
    public static final Set<String> SEMANTIC_ROLES = Set.of(
            "QUESTION", "ANSWER", "EXPLANATION", "CONCLUSION", "INTRODUCTION", "DIRECTORY");
    public static final Set<String> MASTERY_STATES = Set.of("UNKNOWN", "HARD", "FUZZY", "KNOWN");

    private ApiContractValues() {
    }
}