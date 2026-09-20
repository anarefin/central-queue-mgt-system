package com.qms.configuration.catalogue;

import com.qms.platform.Profiles;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The spoken form of a token prefix, per language (ticket 29, FR-DSP-030, FR-I18N-040, FR-I18N-041). Reuses
 * {@code config:service_catalogue}: token prefixes are set on the catalogue, so the same permission covers how
 * they are pronounced.
 */
@RestController
@RequestMapping("/token-prefixes")
@Profile(Profiles.SERVING)
public class PrefixSpokenFormController {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)";

    private final CatalogueService catalogue;

    PrefixSpokenFormController(CatalogueService catalogue) {
        this.catalogue = catalogue;
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/{prefix}/spoken-forms")
    public List<PrefixSpokenForm> spokenForms(@PathVariable String prefix) {
        return catalogue.spokenForms(prefix);
    }

    /** Adding or updating one language's spoken form is what lets a new prefix carrying it go live (FR-I18N-041). */
    @PreAuthorize(PERMISSION)
    @PutMapping("/{prefix}/spoken-forms/{language}")
    public PrefixSpokenForm upsertSpokenForm(@PathVariable String prefix, @PathVariable String language, @RequestBody UpsertSpokenFormRequest request) {
        return catalogue.upsertSpokenForm(prefix, language, request);
    }
}
