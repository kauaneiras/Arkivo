package com.labdms.infrastructure.adapter.out.persistence.adapter;

import com.labdms.domain.model.versioning.Branch;
import com.labdms.domain.port.out.BranchRepository;
import com.labdms.infrastructure.adapter.out.persistence.entity.BranchJpaEntity;
import com.labdms.infrastructure.adapter.out.persistence.mapper.BranchPersistenceMapper;
import com.labdms.infrastructure.adapter.out.persistence.repository.BranchJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class BranchRepositoryAdapter implements BranchRepository {

    private final BranchJpaRepository repository;
    private final BranchPersistenceMapper mapper;

    public BranchRepositoryAdapter(BranchJpaRepository repository, BranchPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Branch save(Branch branch) {
        BranchJpaEntity entity = mapper.toJpaEntity(branch);
        BranchJpaEntity saved = repository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<Branch> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public List<Branch> findByDocumentId(UUID documentId) {
        return repository.findByDocumentId(documentId).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean existsByDocumentIdAndName(UUID documentId, String name) {
        return repository.findByDocumentIdAndName(documentId, name).isPresent();
    }
    
    @Override
    public Optional<Branch> findDefaultByDocumentId(UUID documentId) {
        return repository.findByDocumentIdAndIsDefaultTrue(documentId)
                .map(mapper::toDomain);
    }
}
