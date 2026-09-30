package cn.wangwenzhu.ireader.upgradeconsole.api;

import cn.wangwenzhu.ireader.upgradeconsole.api.dto.ConsoleDtos;
import cn.wangwenzhu.ireader.upgradeconsole.application.UpgradeConsoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * HTTP adapter for uploads, deployment commands and existing console responses.
 */
@RestController
@RequiredArgsConstructor
public class ConsoleController {
    private final UpgradeConsoleService service;

    @GetMapping("/api/overview")
    public ConsoleDtos.OverviewResponse overview() throws IOException {
        return ConsoleDtos.from(service.overview());
    }

    @GetMapping("/api/dashboard")
    public ConsoleDtos.DashboardResponse dashboard() throws IOException {
        return ConsoleDtos.from(service.dashboard());
    }

    @GetMapping("/api/releases")
    public ConsoleDtos.PageResponse<ConsoleDtos.ReleaseResponse> releases(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) throws IOException {
        return ConsoleDtos.releases(service.releases(query, page, size));
    }

    @GetMapping("/api/releases/{id}")
    public ConsoleDtos.ReleaseResponse release(@PathVariable String id) {
        return ConsoleDtos.from(service.release(id));
    }

    @GetMapping("/api/releases/{id}/operations")
    public ConsoleDtos.PageResponse<ConsoleDtos.OperationResponse> operations(
            @PathVariable String id, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) throws IOException {
        return ConsoleDtos.operations(service.operations(id, page, size));
    }

    @GetMapping("/api/operations")
    public ConsoleDtos.PageResponse<ConsoleDtos.OperationResponse> operations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) throws IOException {
        return ConsoleDtos.operations(service.operations(page, size));
    }

    @GetMapping("/api/operations/{id}")
    public ConsoleDtos.OperationResponse operation(@PathVariable String id) {
        return ConsoleDtos.from(service.operation(id));
    }

    @PostMapping(value = "/api/releases", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ConsoleDtos.ReleaseResponse upload(@RequestPart("file") MultipartFile file, @RequestParam long runId)
            throws Exception {
        try (var input = file.getInputStream()) {
            return ConsoleDtos.from(service.upload(input, file.getSize(), runId));
        }
    }

    @PostMapping("/api/releases/{id}/deployments")
    public ConsoleDtos.OperationResponse deploy(@PathVariable String id) throws Exception {
        return ConsoleDtos.from(service.deploy(id));
    }

    @PostMapping("/api/recoveries")
    public ConsoleDtos.OperationResponse restorePublished(@RequestBody ConsoleDtos.ConfirmationRequest request)
            throws IOException {
        return ConsoleDtos.from(service.restorePublished(request.backupId(), request.confirmDataRestore()));
    }

    @PostMapping("/api/operations/{id}/abort")
    public ConsoleDtos.OperationResponse abortInterrupted(@PathVariable String id,
                                                          @RequestBody ConsoleDtos.ConfirmationRequest request)
            throws IOException {
        return ConsoleDtos.from(service.abortInterrupted(id, request.confirmDataRestore()));
    }

    @PostMapping("/api/operations/{id}/recover")
    public ConsoleDtos.OperationResponse recover(@PathVariable String id,
                                                 @RequestBody ConsoleDtos.ConfirmationRequest request)
            throws IOException {
        return ConsoleDtos.from(service.recover(id, request.backupId(), request.confirmDataRestore()));
    }
}