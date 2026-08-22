-- Document types become data instead of code.
--
-- They were a Java enum (IT, PQ, PO, MQ), so adding the type a particular lab
-- actually uses — "FT" for a datasheet, "CAL" for a calibration record — meant
-- editing source and redeploying. Every laboratory has its own document
-- taxonomy, and ISO 9000 does not prescribe one, so this belongs to the quality
-- manager, not to the build.
--
-- The PREFIX is deliberately immutable once documents exist under it. A code
-- like IT-001 is the identifier the lab writes on paper records and refers to
-- in audits; letting it change meaning retroactively would break the
-- traceability the whole system exists to provide. The display name stays
-- editable, and a type that should no longer be used is deactivated rather than
-- deleted, so historical documents keep resolving.

CREATE TABLE IF NOT EXISTS public.document_types (
    id uuid NOT NULL,
    prefix character varying(10) NOT NULL,
    name character varying(150) NOT NULL,
    description text,
    is_active boolean NOT NULL DEFAULT true,
    -- Retention and review cadence sensibly differ per type: a quality manual is
    -- reviewed less often than a work instruction. Null means "use the system
    -- default", which is what V7 put on documents.
    default_review_interval_months integer,
    default_retention_years integer,
    sort_order integer NOT NULL DEFAULT 100,
    created_at timestamp(6) without time zone NOT NULL DEFAULT NOW(),
    updated_at timestamp(6) without time zone NOT NULL DEFAULT NOW(),
    created_by uuid,
    CONSTRAINT document_types_pkey PRIMARY KEY (id),
    CONSTRAINT uk_document_types_prefix UNIQUE (prefix),
    -- The prefix goes into a document code, which is parsed on the way back in.
    -- Letters and digits only keeps that parse unambiguous.
    CONSTRAINT document_types_prefix_format CHECK (prefix ~ '^[A-Z][A-Z0-9]{0,9}$')
);

CREATE INDEX IF NOT EXISTS idx_document_types_active
    ON public.document_types(is_active, sort_order);

-- Seed with the four types that were hardcoded, so nothing that already exists
-- loses its meaning. ON CONFLICT makes the migration safe to re-run.
INSERT INTO public.document_types (id, prefix, name, description, sort_order)
VALUES
    (gen_random_uuid(), 'MQ', 'Manual da Qualidade',
     'Documento de topo do sistema de gestão da qualidade', 10),
    (gen_random_uuid(), 'PQ', 'Procedimento da Qualidade',
     'Procedimento que descreve um processo do sistema da qualidade', 20),
    (gen_random_uuid(), 'PO', 'Procedimento Operacional Padrão',
     'Sequência de passos para uma operação recorrente', 30),
    (gen_random_uuid(), 'IT', 'Instrução de Trabalho',
     'Instrução detalhada para uma tarefa específica de bancada', 40)
ON CONFLICT (prefix) DO NOTHING;

-- Any prefix already present in documents but missing from the seed above is
-- adopted rather than orphaned. Without this, a document whose type came from a
-- hand-edited row would stop resolving after the switch to the table.
INSERT INTO public.document_types (id, prefix, name, description, sort_order)
SELECT gen_random_uuid(), d.doc_type, d.doc_type, 'Tipo importado automaticamente', 900
  FROM (SELECT DISTINCT doc_type FROM public.documents WHERE doc_type IS NOT NULL) d
 WHERE d.doc_type ~ '^[A-Z][A-Z0-9]{0,9}$'
ON CONFLICT (prefix) DO NOTHING;

COMMENT ON COLUMN public.document_types.prefix IS
    'Immutable once documents exist under it: it is part of every code already issued.';
