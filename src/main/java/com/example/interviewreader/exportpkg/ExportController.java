package com.example.interviewreader.exportpkg;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.excelpkg.ExcelPackageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

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
        var format = request.format().toUpperCase(Locale.ROOT);
        var documentPackage = exportService.exportJsonPackage(request.documentId(), request.versionId());
        switch (format) {
            case "JSON_PACKAGE" -> {
                return ResponseEntity.ok(documentPackage);
            }
            case "EXCEL" -> {
                var bytes = excelPackageService.write(documentPackage);
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .header("Content-Disposition", "attachment; filename=\"interview-reader-export.xlsx\"")
                        .body(bytes);
            }
            case "MARKDOWN" -> {
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                        .header("Content-Disposition", "attachment; filename=\"interview-reader-export.md\"")
                        .body(markdownPackageService.write(documentPackage));
            }
            case "STATIC_HTML" -> {
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                        .header("Content-Disposition", "attachment; filename=\"interview-reader-export.html\"")
                        .body(staticHtmlPackageService.write(documentPackage));
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "MVP currently supports JSON_PACKAGE, EXCEL, MARKDOWN and STATIC_HTML exports");
    }
}
