package com.qms.configuration.catalogue;

import java.util.UUID;

/**
 * Whether anything issued to visitors refers to a service. A service that has tickets can only be deactivated
 * (FR-CFG-015); the catalogue asks this before it deletes anything.
 */
interface ServiceUsage {

    boolean hasTickets(UUID serviceId);
}
