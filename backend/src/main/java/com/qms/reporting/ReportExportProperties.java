package com.qms.reporting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param asyncThresholdRows a report export over this many rows is generated in the background instead of inline
 *     (FR-RPT-004, default 50,000)
 * @param linkTtlHours how long a finished background export's download link stays valid (FR-RPT-004, default 24h)
 * @param storageDir where a background export's finished file is kept until its link expires
 * @param pollCron when {@code ReportExportJobScheduler} sweeps queued jobs; {@code -} disables it so a test can
 *     drive a tick itself, the same convention {@code qms.reporting.refresh-cron} uses
 */
@ConfigurationProperties("qms.reporting.export")
public record ReportExportProperties(
        @DefaultValue("50000") long asyncThresholdRows,
        @DefaultValue("24") long linkTtlHours,
        @DefaultValue("./data/report-exports") String storageDir,
        @DefaultValue("*/3 * * * * *") String pollCron) {}
