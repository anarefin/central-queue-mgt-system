package com.qms.identity;

import jakarta.validation.constraints.Size;

record DisableUserRequest(@Size(max = 500) String reason) {}
