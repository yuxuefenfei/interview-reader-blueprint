package com.example.interviewreader.upgradeconsole;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "upgrade")
public record UpgradeSettings(
        Path appDir,
        Path dataDir,
        Path stateDir,
        Path dbDefaultsFile,
        String dbName,
        Path mysqlBin,
        Path mysqldumpBin,
        String githubOwner,
        String githubRepo,
        String githubToken,
        String adminUser,
        String adminPassword,
        String publicOrigin,
        String internalToken,
        int mainPort) {

    public Path jar() {
        return appDir.resolve("interview-reader.jar");
    }

    public Path daemon() {
        return appDir.resolve("daemon.sh");
    }

    public Path marker() {
        return appDir.resolve("tmp/upgrade.maintenance");
    }
}
