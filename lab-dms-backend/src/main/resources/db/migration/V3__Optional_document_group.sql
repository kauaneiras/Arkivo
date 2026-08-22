-- Document group/sector becomes optional (documents can exist without a group)
ALTER TABLE public.documents ALTER COLUMN document_group_id DROP NOT NULL;
