CREATE TABLE app_private.support_tickets (
    id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES public.users(firebase_uid),
    title VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED')),
    admin_remark TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX support_tickets_user_idx ON app_private.support_tickets(user_id,created_at DESC);
CREATE INDEX support_tickets_status_idx ON app_private.support_tickets(status,created_at DESC);
CREATE TABLE app_private.support_ticket_attachments (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES app_private.support_tickets(id) ON DELETE CASCADE,
    secure_url TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX support_attachments_ticket_idx ON app_private.support_ticket_attachments(ticket_id);
ALTER TABLE app_private.support_tickets ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.support_ticket_attachments ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON app_private.support_tickets,app_private.support_ticket_attachments FROM PUBLIC;