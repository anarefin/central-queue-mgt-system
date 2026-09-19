package com.qms.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param exportMaxRows hard cap on rows in one CSV export, so an export cannot exhaust the server */
@ConfigurationProperties("qms.audit")
record AuditProperties(@DefaultValue("100000") int exportMaxRows) {}
