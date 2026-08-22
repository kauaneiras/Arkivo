package com.labdms.domain.port.in;

import com.labdms.domain.model.versioning.Branch;

import com.labdms.application.dto.command.MergeBranchCommand;
import java.util.UUID;

/**
 * Use case for merging a branch into the main branch.
 * Merge is only allowed after an approved review.
 */
public interface MergeBranchUseCase {

    Branch execute(MergeBranchCommand command);
}
