package com.example.interviewreader.management;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reader/document-deletions")
@RequiredArgsConstructor
public class DeletedDocumentTombstoneController {
    private final DeletedDocumentTombstoneService service;


    @GetMapping
    public List<ManagementDtos.DeletedDocumentTombstone> recent() {
        return service.recent();
    }
}