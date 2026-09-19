package com.qms.configuration.site;

import jakarta.validation.constraints.Size;

record DeactivateRequest(@Size(max = 500) String reason) {}
