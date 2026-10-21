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

    /**
     * Grava E descarrega para a base, na hora.
     *
     * <p>{@code save} sozinho apenas põe a entidade no contexto de persistência:
     * o INSERT só sai quando o Hibernate decide descarregar, normalmente no fim
     * da transação. Só que a árvore de ficheiros de uma nova versão é copiada
     * logo a seguir por SQL direto — {@code INSERT INTO project_files ... SELECT}
     * — e o SQL direto não passa pelo Hibernate nem o obriga a descarregar. A
     * linha do commit ainda não existia quando as linhas dos ficheiros
     * tentavam apontar para ela, e a base recusava:
     *
     * <pre>project_files_commit_id_fkey: chave (commit_id) não presente em commits</pre>
     *
     * <p>Não se via em documentos de ficheiro único porque aí não há árvore para
     * copiar: a cópia só corre quando a versão anterior tem ficheiros, ou seja,
     * exatamente nos projetos importados do Overleaf.
     *
     * <p>O flush fica aqui, e não em cada chamador, porque a razão é uma
     * propriedade deste agregado: um commit é referenciado por SQL direto dentro
     * da mesma transação. Quem grava um commit não tem de saber disso.
     */
    @Override
    public Commit save(Commit commit) {
        CommitJpaEntity entity = mapper.toJpaEntity(commit);
        CommitJpaEntity saved = repository.saveAndFlush(entity);
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
        return repository.findByDocumentIdOrderByCreatedAtDesc(documentId).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void delete(UUID id) {
        repository.deleteById(id);
        repository.flush();
    }

    @Override
    public Optional<Commit> findLatestByBranchId(UUID branchId) {
        List<CommitJpaEntity> list = repository.findByBranchIdOrderByCreatedAtDesc(branchId);
        return list.isEmpty() ? Optional.empty() : Optional.of(mapper.toDomain(list.get(0)));
    }
}
