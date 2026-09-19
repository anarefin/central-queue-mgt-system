package com.qms.platform.realtime;

import java.time.Instant;

/**
 * One event as a topic hands it out (SRS §21.3). {@code frame} is the whole JSON text sent to a subscriber, built once
 * however many are listening; {@code recorded} is when the hub took it in, which is what the replay window is measured from.
 */
record Envelope(long seq, String frame, Instant recorded) {}
