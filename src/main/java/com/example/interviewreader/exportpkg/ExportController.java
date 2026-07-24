package com.example.interviewreader.exportpkg;

import com.example.interviewreader.excelpkg.ExcelPackageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/exports")
@RequiredArgsConstructor
public class ExportController {
    private final DocumentPackageExportService exportService;
    private final ExcelPackageService excelPackageService;
    private final MarkdownPackageService markdownPackageService;
    private final StaticHtmlPackageService staticHtmlPackageService;


    @PostMapping
    public ResponseEntity<?> export(@Valid @RequestBody ExportRequest request) {
        var documentPackage = exportService.exportJsonPackage(request.documentId(), request.versionId());
        var assetUrl = (java.util.function.Function<String, String>) assetKey -> "/assets/versions/" + request.versionId() + "/" + assetKey;
        return switch (request.format()) {
            case JSON_PACKAGE -> ResponseEntity.ok(documentPackage);
            case EXCEL -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header("Content-Disposition", "attachment; filename=\"interview-reader-export.xlsx\"")
                    .body(excelPackageService.write(documentPackage));
            case MARKDOWN -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                    .header("Content-Disposition", "attachment; filename=\"interview-reader-export.md\"")
                    .body(markdownPackageService.write(documentPackage, assetUrl));
            case STATIC_HTML -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                    .header("Content-Disposition", "attachment; filename=\"interview-reader-export.html\"")
                    .body(staticHtmlPackageService.write(documentPackage, assetUrl));
        };
    }
}
