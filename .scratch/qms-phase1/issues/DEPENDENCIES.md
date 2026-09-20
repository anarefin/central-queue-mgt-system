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
28-display-now-serving-table               24,11           todo     —        —                   —
29-voice-announcements                     28,12           todo     —        —                   —
30-display-layouts-and-notice-board        28              todo     —        —                   —
31-journeys-and-multi-stop-visits          22,10           todo     —        —                   —
32-appointment-availability                21              todo     —        —                   —
33-staff-appointment-booking               32,22           todo     —        —                   —
34-reschedule-cancel-waitlist              33              todo     —        —                   —
35-appointment-check-in                    33,26,09        todo     —        —                   —
36-appointment-no-shows                    33              todo     —        —                   —
37-visitor-ticket-page-pwa                 11,19           todo     —        —                   —
38-notification-pipeline-in-app            11,02           todo     —        —                   —
39-web-push-channel                        38,37           todo     —        —                   —
40-email-channel-and-appointment-messages  38,34           todo     —        —                   —
41-visitor-otp-login-self-service          37,40,34        todo     —        —                   —
42-remote-join                             37,21           todo     —        —                   —
43-remote-arrival-and-forfeit              42,39,12        todo     —        —                   —
44-internet-loss-degradation               43              todo     —        —                   —
45-post-service-feedback                   37              todo     —        —                   —
46-live-dashboard                          18,16,11        todo     —        —                   —
47-threshold-alerts                        46,24,38        todo     —        —                   —
48-reporting-store-and-token-report        10,15           todo     —        —                   —
49-report-exports                          48,38           todo     —        —                   —
50-operational-reports                     48              todo     —        —                   —
51-domain-reports-and-planning-views       50,36,31,45     todo     —        —                   —
52-scheduled-report-delivery               49,40           todo     —        —                   —
53-retention-purge-bi-access               50              todo     —        —                   —
54-privacy-controls                        22,28,38        todo     —        —                   —
55-config-versioning-and-bundle            09,08,21        todo     —        —                   —
56-vertical-profiles-and-setup-wizard      55,29,27        todo     —        —                   —
57-outbound-webhooks                       11              todo     —        —                   —
58-service-accounts-host-api               33,04           todo     —        —                   —
59-multi-node-operation                    11,08           todo     —        —                   —
60-installer-upgrades-backup               59              todo     —        —                   —
61-acceptance-suite                        56,44,51,60     todo     —        —                   —

# * 26: worker hit a session rate limit mid-ticket, was resumed after reset; elapsed wall-clock
#   spans the pause and is not comparable to other tickets' timings.
# * 27: worker's turn ended waiting on a background gradlew run before committing; resumed to
#   finish verification and commit. Elapsed wall-clock not comparable either.
