package com.example.interviewreader.importpkg;

import com.example.interviewreader.common.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SourceFileStorage {
    private final Path root;

    public SourceFileStorage(ImportProperties properties) {
        this.root = properties.storageDir().toAbsolutePath().normalize();
    }

    public String save(byte[] bytes, String sha256, String fileName) {
        try {
            var target = root.resolve(sha256).resolve(fileName).normalize();
            if (!target.startsWith(root)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid source file name");
            }
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return root.relativize(target).toString().replace('\\', '/');
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot store source file");
        }
    }

    public SourceFile load(String objectKey) {
        try {
            var target = root.resolve(objectKey).normalize();
            if (!target.startsWith(root) || !Files.isRegularFile(target)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Source file not found");
            }
            return new SourceFile(target.getFileName().toString(), Files.readAllBytes(target));
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read source file");
        }
    }

    /** Idempotently deletes one object only when its resolved path remains under the configured storage root. */
    public void deleteIfManaged(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            var target = root.resolve(objectKey).normalize();
            if (!target.startsWith(root)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid managed object key");
            }
            Files.deleteIfExists(target);
            var parent = target.getParent();
            while (parent != null && !parent.equals(root) && parent.startsWith(root)) {
                try (var children = Files.list(parent)) {
                    if (children.findAny().isPresent()) {
                        break;
                    }
                }
                Files.deleteIfExists(parent);
                parent = parent.getParent();
            }
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot delete managed source file");
        }
    }
    public record SourceFile(String fileName, byte[] bytes) {
    }
}