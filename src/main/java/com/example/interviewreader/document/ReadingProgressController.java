package com.example.interviewreader.document;

import com.example.interviewreader.document.DocumentDtos.ReadingProgress;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/reader/reading-progress")
@RequiredArgsConstructor
public class ReadingProgressController {
    private final DocumentQueryService service;


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