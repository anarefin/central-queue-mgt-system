package com.qms.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record UserPage(List<UserView> items, @JsonProperty("next_cursor") String nextCursor) {}
