CREATE TABLE organizations (
    id uuid PRIMARY KEY, name text NOT NULL,
    home_region text NOT NULL CHECK (home_region IN ('eu-central-1','us-east-1','ap-south-1')),
    kms_key_arn text NOT NULL
);
CREATE TABLE departments (
    id uuid PRIMARY KEY, org_id uuid NOT NULL REFERENCES organizations(id), name text NOT NULL,
    UNIQUE(org_id,id)
);
CREATE TABLE app_users (
    id uuid PRIMARY KEY, org_id uuid NOT NULL REFERENCES organizations(id), display_name text NOT NULL,
    role text NOT NULL CHECK(role IN ('MEMBER','MANAGER','ADMIN','COMPLIANCE')), active boolean NOT NULL DEFAULT true,
    UNIQUE(org_id,id)
);
CREATE TABLE user_departments (
    org_id uuid NOT NULL, user_id uuid NOT NULL, dept_id uuid NOT NULL,
    PRIMARY KEY(org_id,user_id,dept_id),
    FOREIGN KEY(org_id,user_id) REFERENCES app_users(org_id,id) ON DELETE CASCADE,
    FOREIGN KEY(org_id,dept_id) REFERENCES departments(org_id,id) ON DELETE CASCADE
);
CREATE TABLE channels (
    id uuid PRIMARY KEY, org_id uuid NOT NULL REFERENCES organizations(id), name text NOT NULL,
    min_role_rank integer NOT NULL CHECK(min_role_rank IN (10,20,30,40)), UNIQUE(org_id,id)
);
CREATE TABLE channel_departments (
    org_id uuid NOT NULL, channel_id uuid NOT NULL, dept_id uuid NOT NULL,
    PRIMARY KEY(org_id,channel_id,dept_id),
    FOREIGN KEY(org_id,channel_id) REFERENCES channels(org_id,id) ON DELETE CASCADE,
    FOREIGN KEY(org_id,dept_id) REFERENCES departments(org_id,id) ON DELETE CASCADE
);
CREATE TABLE documents (
    id uuid PRIMARY KEY, org_id uuid NOT NULL REFERENCES organizations(id), channel_id uuid NOT NULL,
    uploader_id uuid NOT NULL, object_key text NOT NULL UNIQUE, object_version text,
    content_type text NOT NULL, size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 1 AND 52428800),
    min_role_rank integer NOT NULL CHECK(min_role_rank IN (10,20,30,40)), access_expires_at timestamptz NOT NULL,
    status text NOT NULL CHECK(status IN ('UPLOADING','QUEUED','READY','REVOKED')),
    UNIQUE(org_id,id),
    FOREIGN KEY(org_id,channel_id) REFERENCES channels(org_id,id),
    FOREIGN KEY(org_id,uploader_id) REFERENCES app_users(org_id,id)
);
CREATE TABLE document_departments (
    org_id uuid NOT NULL, document_id uuid NOT NULL, dept_id uuid NOT NULL,
    PRIMARY KEY(org_id,document_id,dept_id),
    FOREIGN KEY(org_id,document_id) REFERENCES documents(org_id,id) ON DELETE CASCADE,
    FOREIGN KEY(org_id,dept_id) REFERENCES departments(org_id,id) ON DELETE CASCADE
);
CREATE INDEX documents_access ON documents(org_id,channel_id,access_expires_at) WHERE status='READY';
-- Only the trusted dispatcher accesses this infrastructure queue. No end-user API exposes it.
CREATE TABLE ingestion_outbox (
    id uuid PRIMARY KEY, org_id uuid NOT NULL, channel_id uuid NOT NULL, payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ingestion_outbox_created ON ingestion_outbox(created_at);
ALTER TABLE organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON organizations
    USING(id = NULLIF(current_setting('app.org_id',true),'')::uuid)
    WITH CHECK(id = NULLIF(current_setting('app.org_id',true),'')::uuid);
DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['departments','app_users','user_departments','channels','channel_departments','documents','document_departments'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
        EXECUTE format($policy$CREATE POLICY tenant_isolation ON %I USING (org_id = NULLIF(current_setting('app.org_id',true),'')::uuid) WITH CHECK (org_id = NULLIF(current_setting('app.org_id',true),'')::uuid)$policy$,t);
    END LOOP;
END $$;
