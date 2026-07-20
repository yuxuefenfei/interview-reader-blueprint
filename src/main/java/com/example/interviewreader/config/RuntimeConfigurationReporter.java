package com.example.interviewreader.config;

import com.example.interviewreader.importpkg.ImportProperties;
import com.example.interviewreader.management.DocumentDeletionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 在启动完成后输出不含凭据的关键运行配置，便于部署核对最终生效值。 */
@Component
public class RuntimeConfigurationReporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeConfigurationReporter.class);

    private final ServerProperties server;
    private final UploadProperties upload;
    private final ImportProperties imports;
    private final DocumentDeletionProperties deletion;

    public RuntimeConfigurationReporter(ServerProperties server, UploadProperties upload,
                                        ImportProperties imports, DocumentDeletionProperties deletion) {
        this.server = server;
        this.upload = upload;
        this.imports = imports;
        this.deletion = deletion;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        LOGGER.info(
                "Runtime configuration port={} uploadMax={} converter={} storage={} importWorker={}/{} deletionWorker={}/{}",
                server.getPort(), upload.displayMaxSize(), imports.converterVersion(),
                imports.storageDir().toAbsolutePath().normalize(), imports.importWorker().enabled(),
                imports.importWorker().maxConcurrency(), deletion.workerEnabled(), deletion.maxConcurrency());
    }
}