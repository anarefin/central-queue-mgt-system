# QMS — Domain Context

The shared language for the Queue Management System. Use these terms verbatim in code, APIs, tests and the SRS.
Decisions behind the harder terms live in [docs/adr/](docs/adr/).

## Structure

### Organisation
The client. Exactly one per installation (single-tenant).

### Site
The operational unit (a branch or a campus) that owns business hours, timezone, a token numbering space and
Visits. A Site may span several buildings. See [ADR-0002](docs/adr/0002-site-is-the-operational-unit.md).
_Avoid:_ "building", "branch" as synonyms for Site.

### Zone
A waiting area inside a Site, labelled by building and floor (`building_label`, `floor_label`). Owns displays
and speakers; announcements are scoped to it.

### Counter
A physical or logical serving position (window, desk, consultation room) inside a Zone.

### Service group
A department or clinic within a Site. Owns a set of Services and one Team.

### Service
The thing a Visitor queues for. Owns SLA wait target, expected handling time and token prefix.

### Team
The set of Agents who can serve a Service group.

## People

### Visitor
Any person who joins a queue (customer, patient, producer, citizen). May be pre-registered or anonymous.

### Agent
A staff member who serves Tickets from a Counter session.

## Serving

### Counter session
An Agent's occupancy of one Counter, from open to close. Routing sees sessions, not agents or counters.
A Counter holds at most one open session.

### Visit
One Visitor's presence at one Site on one occasion. Created implicitly with the first Ticket; every Ticket
belongs to exactly one Visit. Scopes "not callable at two counters at once" and the `paused` state.
See [ADR-0007](docs/adr/0007-visit-always-journey-is-a-plan.md).

### Journey
An optional plan of stops on a Visit — an ordered or unordered list of Services, from a template or ad hoc.
Holds Services, not Tickets.

### Journey stop
One planned Service within a Journey; realised by a Ticket when that stop is issued.

### Ticket
One Visitor waiting for one Service. A change of Service always means a new Ticket.

### Token number
The human-readable label printed and announced, e.g. `S-042`. Allocated once per Visit chain and shared by
Successor tickets. Always rendered in Western Arabic digits.

### Transfer
Closing a Ticket as `transferred` and opening a Successor ticket for another Service, Counter or Agent.
See [ADR-0006](docs/adr/0006-transfer-creates-successor-ticket.md).

### Successor ticket
The Ticket created by a Transfer. Same Visit and Token number, linked by `predecessor_ticket_id`.

### Session binding
The link from a `called`, `serving` or `held` Ticket to the Counter session that owns it. It is what "own
record" means for an Agent and what prevents two counters calling the same Ticket.
See [ADR-0008](docs/adr/0008-ticket-ownership-is-session-binding.md).

### Hold
Park a Ticket being served, keeping its Session binding, so the Counter can call the next Ticket.

### Re-announce
Replay the call for a `called` Ticket. No state change. _Avoid:_ "recall".

### Miss
The Agent declares a called Visitor absent. The Ticket re-enters the queue at the configured re-entry position,
or becomes `no_show` once the miss limit is exceeded. _Avoid:_ "recall".
See [ADR-0005](docs/adr/0005-recall-split-into-reannounce-and-miss.md).

## Ordering

### Priority class
A configurable class (e.g. Senior citizen, Emergency) carrying a Head start and a maximum wait target.

### Score
The number the queue engine orders waiting Tickets by, measured in minutes. Highest is served next.
See [ADR-0003](docs/adr/0003-priority-as-additive-headstart.md).

### Head start
The minutes of virtual waiting a Priority class grants on arrival. _Avoid:_ "weight".

### Score adjustment
A signed minute offset applied once by a positional move (delay, forfeit, Miss re-entry, call timeout).
Never touches real wait. See [ADR-0004](docs/adr/0004-positional-moves-are-score-adjustments.md).

### Escalation
The bonus that pushes a Ticket ahead of all lower-priority Tickets once its real wait passes its class's
maximum wait. Overrides any Score adjustment.

## Entry

### Appointment
A reserved slot that converts into a Ticket at check-in.

### Remote ticket
A Ticket joined from the mobile web app before the Visitor is on site. Accrues wait like any other Ticket but
cannot be called until checked in.

### Vertical profile
A packaged configuration seed (banking, healthcare, …) that adapts labels, catalogue and defaults to an industry.

## Relationships

- Organisation → Site → Zone → Counter
- Site → Service group → Service; Service group ↔ Team (1:1)
- Counter ↔ Service (many-to-many, with preference weight)
- Visitor → Visit → Ticket; Visit → Journey (0..1) → Journey stop → Ticket (0..1)
- Ticket → Successor ticket (0..1, via Transfer)
- Counter session → Ticket (Session binding, while `called` / `serving` / `held`)
- Appointment → Ticket (0..1, at check-in)

## Resolved ambiguities

- **"Recall"** meant both re-announcing a called ticket and re-queueing a missed one. Split into
  **Re-announce** and **Miss** (ADR-0005).
- **"Journey"** was defined both as a list of services and as a list of tickets. A Journey is a plan of
  services; tickets realise its stops; the **Visit** groups the tickets (ADR-0007).
- **"Site"** was "one building or branch". A Site is the operational unit; buildings are a Zone attribute (ADR-0002).
- **"Weight"** (priority multiplier) is replaced by **Head start** in minutes (ADR-0003).
