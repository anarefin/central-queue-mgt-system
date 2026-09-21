package com.qms.reporting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param location the client-specified directory a nightly extract of the reporting fact tables is written to
 *     (FR-INT-060)
 * @param cron when {@code ReportingExtractScheduler} runs the nightly extract; {@code -} disables it so a test can
 *     drive a tick itself, the same convention {@code qms.reporting.export.poll-cron} already uses
 */
@ConfigurationProperties("qms.reporting.extract")
record ReportingExtractProperties(@DefaultValue("./data/reporting-extracts") String location, @DefaultValue("0 0 3 * * *") String cron) {}
