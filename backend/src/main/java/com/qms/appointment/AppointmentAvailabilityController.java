package com.qms.appointment;

import com.qms.appointment.AppointmentAvailabilityViews.AppointmentException;
import com.qms.appointment.AppointmentAvailabilityViews.Availability;
import com.qms.appointment.AppointmentAvailabilityViews.ExceptionRequest;
import com.qms.appointment.AppointmentAvailabilityViews.Exceptions;
import com.qms.appointment.AppointmentAvailabilityViews.Settings;
import com.qms.appointment.AppointmentAvailabilityViews.Templates;
import com.qms.appointment.AppointmentAvailabilityViews.TemplatesRequest;
import com.qms.platform.Profiles;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Appointment availability (SRS §9.1, §9.2): slot templates and exceptions at Service, Team or Agent level, a
 * Service's booking-horizon settings, and the search by Service then date. Each method carries its permission here,
 * where the build-time check looks for it (FR-CFG-108); {@link AppointmentAvailabilityService} repeats it (API-016).
 */
@RestController
@Profile(Profiles.SERVING)
public class AppointmentAvailabilityController {

    private final AppointmentAvailabilityService availability;

    AppointmentAvailabilityController(AppointmentAvailabilityService availability) {
        this.availability = availability;
    }

    @PreAuthorize(AppointmentAvailabilityService.CATALOGUE)
    @GetMapping("/appointment-templates/{level}/{targetId}")
    public Templates templates(@PathVariable String level, @PathVariable UUID targetId) {
        return availability.templates(level, targetId);
    }

    @PreAuthorize(AppointmentAvailabilityService.CATALOGUE)
    @PutMapping("/appointment-templates/{level}/{targetId}")
    public Templates setTemplates(@PathVariable String level, @PathVariable UUID targetId, @RequestBody(required = false) TemplatesRequest request) {
        return availability.setTemplates(level, targetId, request);
    }

    @PreAuthorize(AppointmentAvailabilityService.CATALOGUE)
    @GetMapping("/appointment-exceptions/{level}/{targetId}")
    public Exceptions exceptions(@PathVariable String level, @PathVariable UUID targetId) {
        return availability.exceptions(level, targetId);
    }

    @PreAuthorize(AppointmentAvailabilityService.CATALOGUE)
    @PostMapping("/appointment-exceptions/{level}/{targetId}")
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentException addException(@PathVariable String level, @PathVariable UUID targetId, @RequestBody(required = false) ExceptionRequest request) {
        return availability.addException(level, targetId, request);
    }

    @PreAuthorize(AppointmentAvailabilityService.CATALOGUE)
    @DeleteMapping("/appointment-exceptions/{level}/{targetId}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeException(@PathVariable String level, @PathVariable UUID targetId, @PathVariable UUID id) {
        availability.removeException(level, targetId, id);
    }

    @PreAuthorize(AppointmentAvailabilityService.CATALOGUE)
    @GetMapping("/services/{id}/appointment-settings")
    public Settings settings(@PathVariable UUID id) {
        return availability.settings(id);
    }

    @PreAuthorize(AppointmentAvailabilityService.CATALOGUE)
    @PutMapping("/services/{id}/appointment-settings")
    public Settings setSettings(@PathVariable UUID id, @RequestBody(required = false) Settings request) {
        return availability.setSettings(id, request);
    }

    @PreAuthorize(AppointmentAvailabilityService.SEARCH)
    @GetMapping("/services/{id}/appointments/availability")
    public Availability search(@PathVariable UUID id, @RequestParam String date) {
        return availability.search(id, date);
    }
}
