package com.example.interviewreader.importpkg;

import com.example.interviewreader.common.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SourceFileStorage {
    private final Path root;

    public SourceFileStorage(@Value("${interview-reader.storage-dir:./data/import-sources}") String storageDir) {
        this.root = Path.of(storageDir).toAbsolutePath().normalize();
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

    public record SourceFile(String fileName, byte[] bytes) {
    }
}
