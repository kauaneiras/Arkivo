-- The laboratory's own name, logo and colour.
--
-- "Lab DMS" is the name of the software, not of the organisation running it. A
-- quality system is an internal, official-looking thing: people trust it partly
-- because it looks like it belongs to their company. Hardcoding our brand into
-- their document system was a product decision made by accident.
--
-- One row, always. A single installation serves one laboratory, so a settings
-- TABLE with many rows would only create the question of which one is current.
-- The CHECK pins the id so a second row cannot be inserted.

CREATE TABLE IF NOT EXISTS public.branding (
    id integer NOT NULL DEFAULT 1,
    organisation_name character varying(120) NOT NULL DEFAULT 'Lab DMS',
    -- Stored inline rather than on the volume: a logo is small, changes rarely,
    -- and keeping it in the row means a backup of the database restores a
    -- working installation with its identity intact.
    logo_data_url text,
    -- Everything else about the theme (spacing, typography, the light and dark
    -- palettes) stays fixed. Only the accent moves, and the shades are derived
    -- from it, so an administrator cannot produce an unreadable interface by
    -- choosing badly.
    primary_color character varying(7) NOT NULL DEFAULT '#2563EB',
    updated_at timestamp(6) without time zone NOT NULL DEFAULT NOW(),
    updated_by uuid,
    CONSTRAINT branding_pkey PRIMARY KEY (id),
    CONSTRAINT branding_single_row CHECK (id = 1),
    CONSTRAINT branding_colour_format CHECK (primary_color ~* '^#[0-9A-F]{6}$')
);

INSERT INTO public.branding (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

COMMENT ON TABLE public.branding IS
    'Single-row installation identity: name, logo and accent colour shown in the interface.';
