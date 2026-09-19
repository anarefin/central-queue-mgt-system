package com.qms.identity;

import java.util.List;
import java.util.Set;

/**
 * Seam for FR-INT-002: mapping an external group claim (an AD or IdP group) to role assignments, so an AD group can
 * grant Team Admin without manual assignment. Phase 1 has no external provider and registers none; assignments a
 * mapper produces are stored with source {@code external} and never disturb manual ones.
 */
public interface RoleMapper {

    List<RoleAssignment> map(Set<String> externalGroups);
}
