package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code preference_weight} defaults to 1, the primary counter. */
record LinkCounterRequest(@JsonProperty("preference_weight") Integer preferenceWeight) {}
