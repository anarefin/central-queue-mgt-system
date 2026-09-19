package com.qms.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record LoginRequest(@NotBlank @Size(max = 200) String username, @NotBlank @Size(max = 1000) String password) {}
