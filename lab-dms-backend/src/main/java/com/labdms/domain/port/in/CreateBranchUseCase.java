package com.labdms.domain.port.in;

import com.labdms.domain.model.versioning.Branch;

import com.labdms.application.dto.command.CreateBranchCommand;
import java.util.UUID;

/**
 * Use case for creating a new branch from an existing branch head.
 */
public interface CreateBranchUseCase {

    Branch execute(CreateBranchCommand command);
}
