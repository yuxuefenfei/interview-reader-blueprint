package com.example.interviewreader.document;

import com.example.interviewreader.document.DocumentDtos.BookmarkDto;
import com.example.interviewreader.document.DocumentDtos.BookmarkRequest;
import com.example.interviewreader.document.DocumentDtos.NoteDto;
import com.example.interviewreader.document.DocumentDtos.NoteRequest;
import com.example.interviewreader.document.DocumentDtos.ReviewStateDto;
import com.example.interviewreader.document.DocumentDtos.ReviewStateRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
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

    @PutMapping("/review-states/{nodeId}")
    public ReviewStateDto upsertReviewState(
            @PathVariable UUID nodeId,
            @Valid @RequestBody ReviewStateRequest request
    ) {
        return service.upsertReviewState(nodeId, request);
    }
}
