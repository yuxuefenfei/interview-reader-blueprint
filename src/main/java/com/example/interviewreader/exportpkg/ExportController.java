package com.example.interviewreader.exportpkg;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.excelpkg.ExcelPackageService;
import com.example.interviewreader.importpkg.DocumentPackage;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exports")
public class ExportController {
    private final DocumentPackageExportService exportService;
    private final ExcelPackageService excelPackageService;
    private final MarkdownPackageService markdownPackageService;
    private final StaticHtmlPackageService staticHtmlPackageService;

    public ExportController(
            DocumentPackageExportService exportService,
            ExcelPackageService excelPackageService,
            MarkdownPackageService markdownPackageService,
            StaticHtmlPackageService staticHtmlPackageService
    ) {
        this.exportService = exportService;
        this.excelPackageService = excelPackageService;
        this.markdownPackageService = markdownPackageService;
        this.staticHtmlPackageService = staticHtmlPackageService;
    }

    @PostMapping
    public ResponseEntity<?> export(@Valid @RequestBody ExportRequest request) {
        var format = request.format().toUpperCase(Locale.ROOT);
        var documentPackage = exportService.exportJsonPackage(request.documentId(), request.versionId());
        if ("JSON_PACKAGE".equals(format)) {
            return ResponseEntity.ok(documentPackage);
        }
        if ("EXCEL".equals(format)) {
            var bytes = excelPackageService.write(documentPackage);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header("Content-Disposition", "attachment; filename=\"interview-reader-export.xlsx\"")
                    .body(bytes);
        }
        if ("MARKDOWN".equals(format)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                    .header("Content-Disposition", "attachment; filename=\"interview-reader-export.md\"")
                    .body(markdownPackageService.write(documentPackage));
        }
        if ("STATIC_HTML".equals(format)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                    .header("Content-Disposition", "attachment; filename=\"interview-reader-export.html\"")
                    .body(staticHtmlPackageService.write(documentPackage));
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "MVP currently supports JSON_PACKAGE, EXCEL, MARKDOWN and STATIC_HTML exports");
    }
}
