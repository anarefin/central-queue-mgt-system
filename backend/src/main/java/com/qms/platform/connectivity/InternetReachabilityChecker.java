package com.qms.platform.connectivity;

import java.net.URI;

/**
 * Whether one external target answers at all, right now (ticket 44). {@link InternetConnectivityMonitor} only cares
 * whether the Site's own uplink is open, not the target's own health, so any answer — even an error status — counts;
 * only a timeout or connection failure does not. A test substitutes a fake implementation rather than reaching the
 * real internet, the same shape {@code VisitorDirectory} already gives {@code VisitorDirectoryGateway}.
 */
public interface InternetReachabilityChecker {

    boolean reachable(URI target);
}
