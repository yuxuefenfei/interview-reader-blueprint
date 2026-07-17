package com.example.interviewreader.management;

import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.persistence.mapper.DocumentDeletionJobMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DeletedDocumentTombstoneService {
    private static final int MAX_TOMBSTONES_PER_SYNC = 1000;
    private final DocumentDeletionJobMapper jobMapper;
    private final DocumentDeletionProperties properties;

    public List<ManagementDtos.DeletedDocumentTombstone> recent() {
        var cutoff = OffsetDateTime.now().minus(properties.tombstoneRetention());
        return jobMapper.selectCompletedSince(AppConstants.LOCAL_USER_ID.toString(), cutoff, MAX_TOMBSTONES_PER_SYNC).stream()
                .map(job -> new ManagementDtos.DeletedDocumentTombstone(
                        java.util.UUID.fromString(job.documentId), job.completedAt))
                .toList();
    }

    @Scheduled(cron = "0 17 3 * * *")
    public void purgeExpired() {
        jobMapper.deleteExpired(OffsetDateTime.now().minus(properties.tombstoneRetention()));
    }
}