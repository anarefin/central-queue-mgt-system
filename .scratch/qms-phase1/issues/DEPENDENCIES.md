# Dependency graph — qms-phase1
# Execution order is filename order. Status here is authoritative, not in the tickets.
# ticket                                   blocked-by      status   elapsed  diffstat            commits

01-walking-skeleton                        —               done     —        (pre-landed)        eca393f..bb9b6df
02-i18n-foundation                         01              done     —        (pre-landed)        eca393f..bb9b6df
03-staff-login-jwt                         01              done     —        (pre-landed)        eca393f..bb9b6df
04-roles-users-audit                       03              done     —        (pre-landed)        eca393f..bb9b6df
05-site-zone-counter-admin                 02,04           done     17m      41 files, +2730 −15 bd1bd0b..0b67c9a
06-service-catalogue-admin                 05              done     22m      53 files, +4229 −20 0b67c9a..851d072
07-reception-issues-walk-in-ticket         06              done     19m      36 files, +2712 −20 851d072..bedfa1a
08-configurable-numbering-reset            07              done     25m      38 files, +2626 −38 bedfa1a..abef1c6
09-queue-ordering-engine                   07              done     21m      48 files, +3284 −81 abef1c6..9360f97
10-counter-session-call-serve-complete     09              done     28m      49 files, +3641 −26 9360f97..2a569ed
11-realtime-hub                            10              done     31m      47 files, +3653 −31 2a569ed..ddb8a9d
12-reannounce-and-miss                     10              done     27m      31 files, +1101 −39 ddb8a9d..2a0d063
13-hold-and-force-close                    10              done     17m      26 files, +1102 −46 2a0d063..ce80940
14-reauth-revocation-user-disable          11,13           done     21m      23 files, +1303 −45 ce80940..123c8c4
15-transfer-successor-ticket               10              done     22m      29 files, +1712 −45 123c8c4..8fd9834
16-breaks-and-availability                 10              done     25m      50 files, +2861 −36 8fd9834..fa4daff
17-call-timeout-out-of-order-parallel      12              done     30m      42 files, +1840 −113 fa4daff..a67a187
18-reprioritise-and-staff-cancel           09,04           done     23m      36 files, +2191 −24 a67a187..9535147
19-wait-estimation                         10,11           done     23m      30 files, +1010 −68 9535147..a849a6d
20-console-visitor-context-and-stats       10              done     16m      26 files, +1053 −15 a849a6d..03c5cbb
21-issuance-rules                          07              done     27m      28 files, +2089 −36 03c5cbb..8f43b29
22-visitor-directory-and-registration      07              done     38m      33 files, +1454 −40 0763409..788f271
23-visitor-csv-import                      22              done     23m      28 files, +1703 −7  fc6216f..02d6ae3
24-device-pairing-and-fleet                05,11           done     52m      68 files, +3390 −42 131ff44..26b1995
25-kiosk-common-path                       24,21,02        done     46m      24 files, +2251 −23 3fbd63b..7ffbaba
26-kiosk-identification-and-selection-tree 25,22           done     n/a*     31 files, +1576 −113 3296f0f..9f3fe5c
27-branding-and-print-template             25              done     n/a*     34 files, +1787 −54 7ceebb1..fa9f8c8
28-display-now-serving-table               24,11           done     51m      31 files, +1868 −37 5a319c3..1eb6a0c
29-voice-announcements                     28,12           done     n/a*     44 files, +1512 −59 7b04c75..c8252ee
30-display-layouts-and-notice-board        28              done     n/a*     82 files, +3891 −123 1eb6a0c..8fcf281
31-journeys-and-multi-stop-visits          22,10           done     42m      36 files, +2276 −16 8edcae0..4969763
32-appointment-availability                21              done     n/a*     17 files, +1909 −7  2529305..b5c2b99
33-staff-appointment-booking               32,22           done     35m      22 files, +1614 −33 e5556ed..ca16543
34-reschedule-cancel-waitlist              33              done     n/a*     17 files, +899 −32  5b75e34..dcd7e75
35-appointment-check-in                    33,26,09        done     n/a*     24 files, +1138 −47 9663f40..dd54b60
36-appointment-no-shows                    33              done     n/a*     14 files, +768 −7   8ab59a0..b8db6a9
37-visitor-ticket-page-pwa                 11,19           done     n/a*     59 files, +2060 −65 ab00088..cc2e211
38-notification-pipeline-in-app            11,02           done     67m      54 files, +3480 −20 cbaf65c..22c0e00
39-web-push-channel                        38,37           done     35m      33 files, +1927 −16 bb5fb5f..9b06532
40-email-channel-and-appointment-messages  38,34           done     n/a*     29 files, +1206 −40 fde12f4..ed2b619
41-visitor-otp-login-self-service          37,40,34        done     42m      50 files, +2980 −57 187eea8..ba82c7b
42-remote-join                             37,21           done     38m      35 files, +1627 −26 7100b01..c00380c
43-remote-arrival-and-forfeit              42,39,12        done     44m      32 files, +1623 −18 c9536a0..de8f5f2
44-internet-loss-degradation               43              done     n/a*     23 files, +722 −17  54a6355..bb513dd
45-post-service-feedback                   37              done     34m      27 files, +1306 −6  927c92b..ba47e54
46-live-dashboard                          18,16,11        done     n/a*     23 files, +2278 −9  68dff54..cc0d99f
47-threshold-alerts                        46,24,38        done     41m      44 files, +2443 −20 5765d45..1f93cfa
48-reporting-store-and-token-report        10,15           done     56m      24 files, +1868 −10 f3074b4..4f7c431
49-report-exports                          48,38           done     n/a*     34 files, +2129 −12 1fc9582..f7da914
50-operational-reports                     48              done     n/a*     24 files, +2506 −25 bf9d047..6e14811
51-domain-reports-and-planning-views       50,36,31,45     done     —        27 files, +3153 −11 86022ac..9af9cb3
52-scheduled-report-delivery               49,40           done     50m      29 files, +2455 −5  596af46..68aba5d
53-retention-purge-bi-access               50              done     32m      28 files, +1319 −9  68aba5d..ccad7a1
54-privacy-controls                        22,28,38        done*    123m     66 files, +2287 −141 3f4489a..814273b
55-config-versioning-and-bundle            09,08,21        done     52m      33 files, +1637 −17  f8d620d..811cb74
56-vertical-profiles-and-setup-wizard      55,29,27        done     34m      39 files, +2132 −9   43dee84..e4f6457
57-outbound-webhooks                       11              done*    125m     48 files, +3064 −10  214d9c5..f6b98ea
58-service-accounts-host-api               33,04           done*    n/a*     20 files, +1029 −21  78757d0..35e4477
59-multi-node-operation                    11,08           done     89m      9 files, +685 −17    d4dc1b8..a86e96e
60-installer-upgrades-backup               59              todo     —        —                   —
61-acceptance-suite                        56,44,51,60     todo     —        —                   —

# * 26, 35, 40, 44: worker hit a session rate limit mid-ticket, was resumed after reset;
#   elapsed wall-clock spans the pause and is not comparable to other tickets' timings.
# * 27, 29, 32, 34: worker's turn ended waiting on a background gradlew run before committing;
#   resumed to finish verification and commit. Elapsed wall-clock not comparable either.
# * 30: commit landed but the run was interrupted before bookkeeping; confirmed done and
#   backfilled on resume, elapsed wall-clock not recorded.
# * 36, 46: worker stalled (no progress 600s) mid-verification; resumed to finish and commit.
# * 37: mid-flight background security review flagged a secret-in-URL issue (ticket secret in
#   query string); relayed to the worker before commit, fixed (fragment + replaceState +
#   no-referrer) and verified in the landed diff.
# * 39: mid-flight background security review flagged an SSRF issue (unvalidated push
#   subscription endpoint URL); relayed to the worker before commit, fixed
#   (PushEndpointSecurity host/scheme validation) and verified in the landed diff.
# * 49: worker ended its turn twice waiting on a backgrounded gradlew check without seeing
#   the result; resumed twice with an explicit foreground-verification instruction before it
#   finished. Also flagged a KNOWN PRE-EXISTING FLAKE, not caused by this ticket: some
#   com.qms.appointment tests hardcode today's real calendar date, so they can collide with
#   actual test-run dates; suite was green this run but worth a follow-up ticket.
# * 50: same background-check pattern as 49 (worker ended turn once waiting on it despite the
#   dispatch prompt now warning against it); resumed once with an explicit foreground
#   instruction and it finished cleanly.
# * 51: found already partway done as uncommitted working-tree state (not started via the
#   normal dispatch) when this run began; a worker was dispatched to review, finish, and
#   commit that existing code rather than redo it. Elapsed wall-clock not comparable.
# * 54: NFR-SEC-011 (app-layer encryption of visitor name/phone/email/notes) delivered in
#   full only for ticket.purpose_note; visitor.name/.phone/.email deliberately left
#   unencrypted — phone/email are FR-INT-010/OTP sign-in's own equality-lookup keys,
#   visitor.name is copied by a raw-SQL INSERT...SELECT into the reporting warehouse, and
#   all three are written by raw-SQL fixtures in ~20 other tickets' own ITs. Reasoning in
#   full in the NFR-SEC-011 row of docs/traceability-matrix.md. Checkbox left unticked by
#   the worker to flag this; everything else on the ticket is fully satisfied. OVERRUN:
#   ~123m agent compute (largest ticket in the run so far) — worth a follow-up ticket for
#   the PII fields if full-coverage encryption is later required.
# * 57: OVERRUN, ~125m — worker ran the full backend suite three separate times chasing
#   down slow/flaky publish overhead before landing a clean run; all criteria satisfied,
#   nothing left unresolved.
# * 58: worker hit a session rate limit mid-ticket, was resumed after reset. Elapsed
#   wall-clock spans the pause and is not comparable. Worker also hit intermittent IT
#   failures (a different, unrelated test each time) that it verified pre-exist on the
#   unmodified base commit too — pre-existing suite flakiness, not caused by this ticket,
#   consistent with the known flake noted at ticket 49. No rate-limiting infra exists for
#   any principal in this codebase yet, so "same rate limits as other principals" is
#   satisfied by construction (host system uses the same guarded endpoints, no bypass).
# * Out-of-band fix (commit fde12f4, parent, not tied to a ticket): a follow-up security
#   review found the already-landed ticket-39 PushEndpointSecurity had an IPv6 ULA
#   (fc00::/7) and IPv4-mapped-address bypass; fixed directly since the active worker at
#   the time (ticket 40) doesn't touch that file.
