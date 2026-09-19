package com.qms.fixtures.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Deliberately neither secured nor public; only ControllerSecurityTest looks at it, to prove the rule bites. */
@RestController
public class UnsecuredController {

    @GetMapping("/fixture/unsecured")
    public String open() {
        return "anyone";
    }
}
