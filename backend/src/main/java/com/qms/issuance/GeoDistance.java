package com.qms.issuance;

/** The great-circle distance between two points (ticket 42, FR-MOB-011's "max distance from Site"): close enough for a
 * "within N km" check, no more precision than that is needed here. */
final class GeoDistance {

    private static final double EARTH_RADIUS_M = 6_371_000;

    private GeoDistance() {}

    /** The Haversine distance between the two points, in metres. */
    static double metersBetween(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2) + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }
}
