package com.qms.feedback;

import java.util.List;

/** A bounded list response; a Team Admin's pending-comment queue and an Agent's own feedback are both small (§27.5 keeps this consistent with other admin lists). */
record Items<T>(List<T> items) {}
