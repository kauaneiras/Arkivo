package com.labdms.infrastructure.adapter.out.persistence.adapter;

import com.labdms.domain.model.versioning.Commit;
import com.labdms.domain.port.out.CommitRepository;
import com.labdms.infrastructure.adapter.out.persistence.entity.CommitJpaEntity;
import com.labdms.infrastructure.adapter.out.persistence.mapper.CommitPersistenceMapper;
import com.labdms.infrastructure.adapter.out.persistence.repository.CommitJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class CommitRepositoryAdapter implements CommitRepository {

    private final CommitJpaRepository repository;
    private final CommitPersistenceMapper mapper;

    public CommitRepositoryAdapter(CommitJpaRepository repository, CommitPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Commit save(Commit commit) {
        CommitJpaEntity entity = mapper.toJpaEntity(commit);
        CommitJpaEntity saved = repository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<Commit> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public List<Commit> findByBranchId(UUID branchId) {
        return repository.findByBranchIdOrderByCreatedAtDesc(branchId).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Commit> findByDocumentId(UUID documentId) {
        return java.util.Collections.emptyList();
    }

    @Override
    public Optional<Commit> findLatestByBranchId(UUID branchId) {
        List<CommitJpaEntity> list = repository.findByBranchIdOrderByCreatedAtDesc(branchId);
        return list.isEmpty() ? Optional.empty() : Optional.of(mapper.toDomain(list.get(0)));
    }
}
