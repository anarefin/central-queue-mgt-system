package com.qms.fixtures.badqueue;

import org.springframework.web.bind.annotation.RestController;

/** Deliberately violates the ADR-0001 seam; only ArchitectureTest imports it, to prove the rule bites. */
@RestController
public class QueueThatKnowsAboutWeb {}
