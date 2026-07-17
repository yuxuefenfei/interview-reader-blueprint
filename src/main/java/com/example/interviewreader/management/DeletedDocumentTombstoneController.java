package com.example.interviewreader.management;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reader/document-deletions")
public class DeletedDocumentTombstoneController {
    private final DeletedDocumentTombstoneService service;

    public DeletedDocumentTombstoneController(DeletedDocumentTombstoneService service) {
        this.service = service;
    }

    @GetMapping
    public List<ManagementDtos.DeletedDocumentTombstone> recent() {
        return service.recent();
    }
}