package com.example.interviewreader.document;

import com.example.interviewreader.document.DocumentDtos.ReadingProgress;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reader/reading-progress")
public class ReadingProgressController {
    private final DocumentQueryService service;

    public ReadingProgressController(DocumentQueryService service) {
        this.service = service;
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<ReadingProgress> get(@PathVariable UUID documentId) {
        var progress = service.getProgress(documentId);
        return progress == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(progress);
    }

    @PutMapping("/{documentId}")
    public ReadingProgress put(@PathVariable UUID documentId, @Valid @RequestBody ReadingProgress progress) {
        return service.upsertProgress(documentId, progress);
    }
}