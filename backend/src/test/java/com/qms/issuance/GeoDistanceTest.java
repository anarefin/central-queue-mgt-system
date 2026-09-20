package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** The Haversine distance used by a remote join's own distance cap (ticket 42, FR-MOB-011). */
class GeoDistanceTest {

    @Test
    void theSamePointIsZeroMetresAway() {
        assertThat(GeoDistance.metersBetween(23.8103, 90.4125, 23.8103, 90.4125)).isZero();
    }

    @Test
    void aKnownPairIsWithinAFewHundredMetresOfItsPublishedDistance() {
        // Dhaka (23.8103, 90.4125) to Gazipur (23.9999, 90.4203): about 21.1 km great-circle.
        double meters = GeoDistance.metersBetween(23.8103, 90.4125, 23.9999, 90.4203);

        assertThat(meters).isCloseTo(21_100, within(500.0));
    }

    @Test
    void oneDegreeOfLatitudeIsAboutOneHundredElevenKilometres() {
        double meters = GeoDistance.metersBetween(0, 0, 1, 0);

        assertThat(meters).isCloseTo(111_195, within(200.0));
    }
}
