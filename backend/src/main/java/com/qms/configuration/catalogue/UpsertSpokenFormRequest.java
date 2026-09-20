package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;

record UpsertSpokenFormRequest(@JsonProperty("spoken_text") String spokenText) {}
