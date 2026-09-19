package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

record AddMemberRequest(@JsonProperty("user_id") UUID userId) {}
