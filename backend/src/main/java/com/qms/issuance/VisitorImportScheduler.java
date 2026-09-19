package com.qms.issuance;

import com.qms.platform.Profiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Scheduled folder pickup (FR-INT-011): every tick, any {@code *.csv} file waiting in {@code
 * qms.visitor.import.pickup-dir} is imported with the saved column mapping, exactly like a manual upload, only with
 * {@code source} {@code scheduled} and no actor. A processed file is moved into a {@code processed/} or {@code
 * failed/} subfolder next to it so it is never picked up twice. Disabled by default: nothing runs unless {@code
 * pickup-dir} is configured. The schedule is a property so a test can switch it off with {@code
 * qms.visitor.import.pickup-cron=-} and drive {@link #tick()} itself, the same convention {@link NumberingScheduler}
 * and {@code CallTimeoutScheduler} use.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
class VisitorImportScheduler {

    private static final Logger log = LoggerFactory.getLogger(VisitorImportScheduler.class);

    private final VisitorImportService imports;
    private final String pickupDir;
    private final Clock clock;

    VisitorImportScheduler(VisitorImportService imports, @Value("${qms.visitor.import.pickup-dir:}") String pickupDir, Clock clock) {
        this.imports = imports;
        this.pickupDir = pickupDir;
        this.clock = clock;
    }

    @Scheduled(cron = "${qms.visitor.import.pickup-cron:-}", zone = "UTC")
    void tick() {
        if (pickupDir == null || pickupDir.isBlank()) return;
        Path directory = Path.of(pickupDir);
        if (!Files.isDirectory(directory)) return;
        for (Path file : csvFiles(directory)) processFile(directory, file);
    }

    private List<Path> csvFiles(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("visitor CSV pickup: could not list {}", directory, e);
            return List.of();
        }
    }

    private void processFile(Path directory, Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            imports.importCsv(file.getFileName().toString(), content, "scheduled", null);
            moveTo(directory, file, "processed");
        } catch (Exception e) {
            log.warn("visitor CSV pickup: {} failed", file, e);
            moveTo(directory, file, "failed");
        }
    }

    private void moveTo(Path directory, Path file, String subdir) {
        try {
            Path target = directory.resolve(subdir);
            Files.createDirectories(target);
            String stamped = clock.instant().toString().replace(':', '-') + "-" + file.getFileName();
            Files.move(file, target.resolve(stamped), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("visitor CSV pickup: could not move {} into {}", file, subdir, e);
        }
    }
}
