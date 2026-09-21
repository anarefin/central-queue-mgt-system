package com.qms.reporting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param purgeCron when {@code RetentionPurgeScheduler} sweeps every data class's own retention policy; {@code -}
 *     disables it so a test can drive a tick itself, the same convention {@code qms.reporting.refresh-cron} and
 *     {@code qms.reporting.export.poll-cron} already use */
@ConfigurationProperties("qms.retention")
record RetentionProperties(@DefaultValue("0 30 2 * * *") String purgeCron) {}
