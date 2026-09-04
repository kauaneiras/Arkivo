/**
 * O que esta pessoa pode fazer com este documento — do lado do ecrã.
 *
 * Espelho de `AccessControlService` no servidor, e nada mais que isso: a decisão
 * que vale é sempre a do servidor, que recusa de qualquer forma. Isto existe
 * para o ecrã não OFERECER o que vai ser recusado, e para poder dizer porquê
 * antes de a pessoa tentar.
 *
 * Está num ficheiro só porque a alternativa é cada tela derivar a regra por sua
 * conta — e então uma delas fica para trás quando a regra muda. Uma tela
 * permissiva a mais mostra um botão que falha; uma restritiva a mais esconde
 * trabalho legítimo. As duas são piores do que um sítio só.
 *
 * Cada função devolve um MOTIVO quando recusa, em vez de um booleano: um botão
 * apagado sem explicação manda a pessoa perguntar a alguém, e a explicação já
 * existe aqui no momento em que a decisão é tomada.
 */

export interface Ator {
  id?: string;
  /** Papéis E permissões juntos, como o servidor os devolve. */
  roles?: string[];
  /** Setores a que pertence. */
  groups?: string[];
}

/** O que uma regra precisa saber sobre o documento. */
export interface AlvoDocumento {
  createdBy?: string | null;
  assignedReviewerId?: string | null;
  documentGroupId?: string | null;
  status?: string | null;
}

/** `null` = pode. Uma string = a chave de tradução que explica a recusa. */
export type Recusa = string | null;

const tem = (a: Ator | null | undefined, p: string) => !!a?.roles?.includes(p);
const eAdmin = (a: Ator | null | undefined) => tem(a, 'DOCUMENT_EDIT_ALL') || tem(a, 'ROLE_ADMIN');
const noSetor = (a: Ator | null | undefined, setor?: string | null) =>
  !setor || !!a?.groups?.includes(setor);

/**
 * Pode gravar neste documento?
 *
 * Precisa de DOCUMENT_EDIT e, além disso, ser administrador, o autor, ou membro
 * do setor. O autor passa sempre: recusar-lhe o próprio rascunho não protege
 * ninguém e torna o documento impossível de terminar.
 */
export function porqueNaoPodeEditar(ator: Ator | null | undefined, doc: AlvoDocumento): Recusa {
  if (!tem(ator, 'DOCUMENT_EDIT') && !tem(ator, 'DOCUMENT_EDIT_ALL')) return 'perm.semEditar';
  if (eAdmin(ator)) return null;
  if (ator?.id && ator.id === doc.createdBy) return null;
  if (!noSetor(ator, doc.documentGroupId)) return 'perm.foraDoSetor';
  return null;
}

/**
 * Pode aprovar ou rejeitar este documento?
 *
 * A ordem importa: a falta de permissão vem primeiro porque é a única que a
 * pessoa resolve pedindo um papel. Ser o autor vem antes do setor porque é
 * definitivo — nenhum administrador consegue autorizar alguém a aprovar o que
 * escreveu, e a separação entre quem escreve e quem aprova é o motivo de o
 * papel de revisor existir (ISO/IEC 17025 §8.3).
 */
export function porqueNaoPodeAprovar(ator: Ator | null | undefined, doc: AlvoDocumento): Recusa {
  if (!tem(ator, 'REVIEW_APPROVE')) return 'perm.semRevisar';
  if (ator?.id && ator.id === doc.createdBy) return 'perm.eOAutor';
  if (eAdmin(ator)) return null;
  if (ator?.id && ator.id === doc.assignedReviewerId) return null;
  if (doc.assignedReviewerId) return 'perm.outroRevisor';
  if (!noSetor(ator, doc.documentGroupId)) return 'perm.foraDoSetor';
  return null;
}

/** Pode criar documentos? */
export function porqueNaoPodeCriar(ator: Ator | null | undefined): Recusa {
  return tem(ator, 'DOCUMENT_CREATE') ? null : 'perm.semCriar';
}
