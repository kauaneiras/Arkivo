-- Data repair: seeded branches were written with status 'ACTIVE', which is not a
-- member of the BranchStatus enum (OPEN / MERGED / CLOSED). Loading such a branch
-- through JPA threw IllegalArgumentException, which broke "save new version" and
-- "submit for review" on every seeded document.
--
-- Existing databases keep their rows; only the invalid status value is corrected.
UPDATE public.branches SET status = 'OPEN' WHERE status NOT IN ('OPEN', 'MERGED', 'CLOSED');

-- Same defensive repair for review requests (OPEN / APPROVED / REJECTED / MERGED).
UPDATE public.review_requests SET status = 'OPEN'
 WHERE status NOT IN ('OPEN', 'APPROVED', 'REJECTED', 'MERGED');
