package com.qms.identity;

import java.util.List;

public record CreateUserCommand(String username, String password, String displayName, String preferredLanguage, List<RoleAssignment> roles) {}
