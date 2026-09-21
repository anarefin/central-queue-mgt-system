/** Post-service feedback (ticket 45, SRS §13.4 FR-MOB-033): a rating and an optional comment against a completed Ticket. */

/** The body of `POST /tickets/{id}/feedback`: `rating` is required (1-5), `comment` is optional. */
export interface FeedbackInput {
  rating: number;
  comment?: string;
}

/** What the visitor ticket page's own submit answers with. */
export interface SubmittedFeedback {
  ticket_id: string;
  rating: number;
  comment: string | null;
  submitted_at: string;
}

/** One row of a Team Admin's review queue (`GET /feedback/pending-comments`): a comment still awaiting a decision. */
export interface PendingFeedbackComment {
  id: string;
  ticket_id: string;
  token_number: string;
  rating: number;
  comment: string;
  submitted_at: string;
}

/**
 * One row of an Agent's own feedback (`GET /feedback/mine`): the rating is always there, the comment only once a
 * Team Admin has approved it (FR-MOB-033) — null until then, even though the visitor already left one.
 */
export interface MyFeedback {
  ticket_id: string;
  token_number: string;
  rating: number;
  comment: string | null;
  submitted_at: string;
}
