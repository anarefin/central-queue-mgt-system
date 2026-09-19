-- Call timeout, out-of-order call and parallel serving (SRS §10.3, §11.2, FR-QUE-032, FR-AGT-010, FR-AGT-011, FR-AGT-012).
-- Parallel serving is a flag per Service with the most tickets a Counter may have in progress (called or serving) at once; a
-- Service that is not parallel keeps the one-at-a-time desk (default 1). `call_timeout_notified_at` remembers that the Agent was
-- prompted for the current call, so the prompt is sent once per call however many nodes run the timeout check.
ALTER TABLE service ADD COLUMN IF NOT EXISTS parallel_serving boolean NOT NULL DEFAULT false;
ALTER TABLE service ADD COLUMN IF NOT EXISTS parallel_limit integer NOT NULL DEFAULT 1 CHECK (parallel_limit BETWEEN 1 AND 20);
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS call_timeout_notified_at timestamptz;
