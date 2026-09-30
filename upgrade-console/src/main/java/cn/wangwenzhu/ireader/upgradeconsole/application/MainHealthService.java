package cn.wangwenzhu.ireader.upgradeconsole.application;

import cn.wangwenzhu.ireader.upgradeconsole.config.UpgradeSettings;
import cn.wangwenzhu.ireader.upgradeconsole.infrastructure.main.MainStatusApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies upgrade eligibility policy to the typed main-app status responses.
 */
@Component
@RequiredArgsConstructor
public class MainHealthService {
    private final UpgradeSettings settings;
    private final MainStatusApiClient main;

    public Snapshot snapshot() {
        var overall = main.overall();
        var liveness = main.liveness();
        var readiness = main.readiness();
        var runtime = main.runtime();
        var blockers = new ArrayList<String>();
        if (!overall.up()) blockers.add("主应用总体健康检查未通过");
        if (!liveness.up()) blockers.add("主应用存活检查未通过");
        if (!readiness.up()) blockers.add("主应用就绪检查未通过");
        if (!readiness.componentUp("db")) blockers.add("数据库健康检查未通过");
        if (!readiness.componentUp("diskSpace")) blockers.add("磁盘空间健康检查未通过");
        if (runtime.maintenance() == null) blockers.add("主应用升级控制接口不可用");
        else if (!settings.marker().toAbsolutePath().normalize().toString().equals(runtime.maintenanceFile()))
            blockers.add("主应用维护门禁路径与控制台不一致");
        if (!settings.dataDir().toAbsolutePath().normalize().toString().equals(runtime.storageDir()))
            blockers.add("主应用文件存储根与备份配置不一致");
        if (!settings.dbName().equals(runtime.databaseName()))
            blockers.add("主应用数据库名与备份配置不一致");
        if (runtime.databaseServerId().isBlank() || "unknown".equals(runtime.databaseServerId()))
            blockers.add("主应用数据库实例身份不可确认");
        else if (Boolean.TRUE.equals(runtime.maintenance())) blockers.add("主应用已有维护门禁");
        return new Snapshot(Instant.now(), overall.body(), liveness.body(), readiness.body(),
                runtime.body(), blockers.isEmpty(), List.copyOf(blockers));
    }

    public JsonNode drain() {
        return main.runtime().body();
    }

    public record Snapshot(Instant checkedAt, JsonNode overall, JsonNode liveness, JsonNode readiness,
                           JsonNode drain, boolean eligible, List<String> blockers) {
    }
}