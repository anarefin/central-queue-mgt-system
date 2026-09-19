package com.qms.configuration.approval;

import jakarta.validation.constraints.Size;

record DecisionRequest(@Size(max = 500) String reason) {}
