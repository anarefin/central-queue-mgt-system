# 0002 — A Site is the operational unit, not a building

Status: Accepted · 2026-09-18

## Context
SRS v1.2 defined a Site as "one building or branch". The launch client is a three-building campus whose UAT
requires one visitor visiting departments in two buildings, with one visit record, a cross-building transfer
and a complete journey report. With Site = building, `visit.site_id` cannot span buildings, a cross-building
transfer becomes a cross-site transfer, and token numbers can collide across buildings.

## Decision
A Site is the operational unit (branch or campus) that shares business hours, timezone, a token numbering space
and Visits. The building is an attribute of the Zone (`building_label`, alongside `floor_label`), shown on
tokens, displays and wayfinding. Journeys and transfers are always intra-site.

## Consequences
- `zone.building_label` added; printed-token field set gains building.
- Cross-site transfer (FR-QUE-054) moves to Phase 2.
- Two buildings of one campus can no longer have different business hours; if that is ever needed they must be
  modelled as separate Sites.

SRS refs: §1.4, §4.2, FR-CFG-003, FR-CFG-031, §18.2, FR-QUE-054, §27.3.
