/** Retention, purge and BI access (ticket 53, SRS §16.3, §25.4-25.5; FR-RPT-021/022, FR-SEC-032/043). The three
 * data classes {@code GET /retention/policies} always answers with, in this fixed order. */
export type RetentionDataClass = "ticket_detail" | "ticket_aggregate" | "audit";

export const RETENTION_DATA_CLASSES: RetentionDataClass[] = ["ticket_detail", "ticket_aggregate", "audit"];

/** Only meaningful for {@code ticket_detail} (FR-RPT-021's "purged or reduced to anonymised aggregates, per the
 * client's choice"); {@code ticket_aggregate} and {@code audit} are always {@code purge}. */
export type RetentionMode = "purge" | "anonymize";

/** One data class's own retention policy, as the API answers it. */
export interface RetentionPolicy {
  data_class: RetentionDataClass;
  retention_months: number;
  mode: RetentionMode;
  updated_at: string;
  updated_by: string | null;
}

/** {@code PUT /retention/policies/{dataClass}}'s body; {@code mode} is only accepted for {@code ticket_detail}. */
export interface RetentionPolicyInput {
  retention_months: number;
  mode?: RetentionMode;
}
