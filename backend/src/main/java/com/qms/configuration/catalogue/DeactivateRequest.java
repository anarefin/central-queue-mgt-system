package com.qms.configuration.catalogue;

import jakarta.validation.constraints.Size;

record DeactivateRequest(@Size(max = 500) String reason) {}
