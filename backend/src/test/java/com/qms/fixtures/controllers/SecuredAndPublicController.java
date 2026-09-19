package com.qms.fixtures.controllers;

import com.qms.platform.security.PublicEndpoint;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SecuredAndPublicController {

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/fixture/secured")
    public String secured() {
        return "ok";
    }

    @PublicEndpoint("fixture")
    @GetMapping("/fixture/public")
    public String open() {
        return "ok";
    }

    @PublicEndpoint("fixture")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/fixture/contradiction")
    public String both() {
        return "confused";
    }
}
