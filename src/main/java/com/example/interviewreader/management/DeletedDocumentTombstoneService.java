package com.example.interviewreader.management;

import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.persistence.DocumentDeletionPersistence;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

import static java.util.UUID.fromString;

@Service
@RequiredArgsConstructor
public class DeletedDocumentTombstoneService {
    private static final int MAX_TOMBSTONES_PER_SYNC = 1000;
    private final DocumentDeletionPersistence deletionPersistence;
    private final DocumentDeletionProperties properties;

    public List<ManagementDtos.DeletedDocumentTombstone> recent() {
        var cutoff = OffsetDateTime.now().minus(properties.tombstoneRetention());
        return deletionPersistence.findCompletedSince(AppConstants.LOCAL_USER_ID.toString(), cutoff, MAX_TOMBSTONES_PER_SYNC)
                .stream()
                .map(job -> new ManagementDtos.DeletedDocumentTombstone(fromString(job.getDocumentId()), job.getCompletedAt()))
                .toList();
    }

    @Scheduled(cron = "0 17 3 * * *")
    public void purgeExpired() {
        deletionPersistence.deleteExpiredJobs(OffsetDateTime.now().minus(properties.tombstoneRetention()));
    }
}
