package com.example.interviewreader.document;

import com.example.interviewreader.document.DocumentDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reader")
public class InteractionController {
    private final InteractionService service;

    public InteractionController(InteractionService service) {
        this.service = service;
    }

    @PostMapping("/bookmarks")
    @ResponseStatus(HttpStatus.CREATED)
    public BookmarkDto createBookmark(@Valid @RequestBody BookmarkRequest request) {
        return service.createBookmark(request);
    }

    @DeleteMapping("/bookmarks/{bookmarkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBookmark(@PathVariable UUID bookmarkId) {
        service.deleteBookmark(bookmarkId);
    }

    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteDto createNote(@Valid @RequestBody NoteRequest request) {
        return service.createNote(request);
    }

    @GetMapping("/review-queue")
    public List<ReviewQueueItem> reviewQueue(
            @RequestParam(required = false) UUID documentId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "false") boolean dueOnly
    ) {
        return service.reviewQueue(documentId, limit, dueOnly);
    }

    @PutMapping("/review-states/{nodeId}")
    public ReviewStateDto upsertReviewState(
            @PathVariable UUID nodeId,
            @Valid @RequestBody ReviewStateRequest request
    ) {
        return service.upsertReviewState(nodeId, request);
    }
}
