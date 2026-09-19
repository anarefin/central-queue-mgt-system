/** A topic's state as of `seq` (SRS §21.1, §21.5): what the realtime hub sends first, and what a polling client asks for. */
export interface TopicSnapshot {
  topic: string;
  seq: number;
  /** Names the run of the topic the seq belongs to; a different epoch means the seq is not comparable. */
  epoch: string;
  data: Record<string, unknown>;
}
