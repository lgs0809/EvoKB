-- Reservation is committed before retrieval. Never refund a failed or interrupted round.
CREATE TABLE query_retrieval_reservation (
  reservation_id VARCHAR(36) PRIMARY KEY,
  session_id VARCHAR(36) NOT NULL REFERENCES query_session(session_id),
  round_no INTEGER NOT NULL CHECK (round_no > 0),
  input_queries JSONB NOT NULL CHECK (jsonb_typeof(input_queries) = 'array'),
  started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (session_id, round_no)
);

-- Preserve already spent budgets and original records when upgrading an existing database.
-- Legacy ordinals beyond the new ceiling remain evidence of usage; they grant no new slots.
INSERT INTO query_retrieval_reservation(reservation_id,session_id,round_no,input_queries,started_at)
SELECT round_id,session_id,round_no,input_queries,started_at FROM retrieval_round;
