/**
 * The visitor mobile web app's own backend surface (SRS §13.1, §20.2; FR-MOB-001, FR-MOB-002; ticket 41): email +
 * OTP sign-in that mints a visitor-role JWT (the anonymous ticket-id-plus-secret path of ticket 37 keeps working
 * unchanged, side by side), the "my account" read model (active tickets, appointment history, saved sites), and
 * saving or removing a site. Appointment self-service (booking, reschedule, cancel) is not here: a registered
 * visitor calls the same {@code com.qms.appointment} endpoints staff do, with {@code hasRole('VISITOR')} added to
 * their authorisation and an object-level "own appointment only" check inside that service (FR-CFG-105's shape),
 * exactly the reuse {@code AppointmentBookingService} already anticipated.
 */
package com.qms.mobile;
