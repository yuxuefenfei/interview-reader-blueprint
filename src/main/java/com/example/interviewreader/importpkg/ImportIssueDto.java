package com.example.interviewreader.importpkg;

public record ImportIssueDto(
        ImportIssueSeverity severity,
        String issueCode,
        String message,
        Integer sourcePage,
        String sectionKey,
        String blockKey,
        String cellRef
) {
    public ImportIssueDto(
            ImportIssueSeverity severity,
            String issueCode,
            String message,
            Integer sourcePage,
            String sectionKey,
            String blockKey
    ) {
        this(severity, issueCode, message, sourcePage, sectionKey, blockKey, null);
    }
}
