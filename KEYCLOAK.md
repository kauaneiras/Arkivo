# Login único com Keycloak

O Lab DMS autentica com contas locais (e-mail e senha, BCrypt) e, opcionalmente,
com um provedor OpenID Connect — o Keycloak na prática. Os dois convivem: uma
instalação pode ter o administrador local e todo o laboratório entrando pelo
SSO.

O que o SSO substitui é a **senha**, não a **conta**. Cada regra do sistema —
quem escreveu, quem aprova, a que setor pertence — está presa a uma linha em
`users`. Na primeira entrada pelo SSO a conta é criada a partir do token; nas
seguintes, os papéis são sincronizados com o token, para que remover alguém de
um grupo no Keycloak faça efeito imediato.

---

## Ligar

No `.env`:

```
SSO_ENABLED=true
SSO_ISSUER_URI=https://sso.seulab.exemplo/realms/laboratorio
SSO_CLIENT_ID=lab-dms
```

E `docker compose up -d backend frontend`. Nada é reconstruído: as variáveis
são lidas na subida. A tela de login passa a mostrar **Entrar com Keycloak**
acima do formulário local.

Para experimentar sem um Keycloak próprio, o `docker-compose.yml` traz um sob o
perfil `sso`, com um *realm* de desenvolvimento importado de
`keycloak/realm-lab-dms.json`:

```bash
docker compose --profile sso up -d
```

| | |
|---|---|
| Console | http://localhost:8180 (admin / admin, ou `KEYCLOAK_ADMIN*` do `.env`) |
| Realm | `lab-dms` |
| Cliente | `lab-dms` (público, PKCE S256) |
| Usuários de teste | `admin.sso@email.com`, `writer.sso@email.com`, `reviewer.sso@email.com`, senha `123456` |
| Grupos | `Qualidade`, `Soldagem`, `Administração` — os mesmos nomes dos setores semeados, para o espelhamento funcionar de imediato |

E no `.env`: `SSO_ISSUER_URI=http://localhost:8180/realms/lab-dms`.

> O *issuer* tem de ser alcançável **pelo backend** (para buscar as chaves) e
> **pelo navegador** (para o login). Com tudo em Docker na mesma máquina,
> `localhost:8180` serve para os dois porque a porta está publicada.

---

## Configurar o seu Keycloak

1. **Realm**: qualquer um já existente.
2. **Cliente** `lab-dms`:
   - *Client authentication*: **off** (cliente público; o navegador não guarda segredo);
   - *Standard flow*: **on**; *Direct access grants*: **off**;
   - *Valid redirect URIs*: `https://dms.seulab.exemplo/*`;
   - *Web origins*: `https://dms.seulab.exemplo`;
   - *Valid post logout redirect URIs*: `https://dms.seulab.exemplo/*`;
   - em *Advanced*, *Proof Key for Code Exchange Code Challenge Method*: `S256`.
3. **Papéis** (realm roles) que mapeiam para os papéis locais:

   | Keycloak | Lab DMS |
   |---|---|
   | `dms-admin` | `ROLE_ADMIN` |
   | `dms-editor` | `ROLE_EDITOR` |
   | `dms-reviewer` | `ROLE_REVIEWER` |
   | `dms-viewer` | `ROLE_VIEWER` |

   Os nomes à esquerda são o padrão em `labdms.sso.role-mapping`
   (`application.yml`) e podem ser trocados. Um papel do Keycloak que já se
   chame `ROLE_EDITOR` mapeia para si próprio sem configuração. Quem entra sem
   nenhum papel mapeado recebe `SSO_DEFAULT_ROLES` (padrão `ROLE_VIEWER`).

4. O token precisa trazer os papéis em `realm_access.roles` (padrão do
   Keycloak). Se o seu realm os põe noutro sítio — por exemplo em
   `resource_access.lab-dms.roles` — ajuste `SSO_ROLES_CLAIM`.
5. **Grupos → setores** (opcional). Crie no cliente `lab-dms` um *mapper* do
   tipo *Group Membership* com *Token Claim Name* `groups` e *Full group path*
   desligado. Um grupo do Keycloak com o mesmo nome de um setor do Lab DMS
   (`Qualidade`, `Soldagem`…) passa a controlar a pertença a esse setor: a cada
   entrada, os setores da pessoa ficam iguais à lista do token. Nomes
   diferentes mapeiam-se em `labdms.sso.group-mapping`. Sem o mapper, o token
   não traz a claim e os setores continuam a ser geridos à mão em
   *Configurações → Setores* — nada é apagado.

---

## O que acontece numa entrada

```
navegador ──(authorize + PKCE)──▶ Keycloak ──(code)──▶ /auth/callback
                                                        │
                                                        ├─ POST token endpoint (code + verifier)
                                                        │
                                                        └─ GET /api/v1/auth/me  Authorization: Bearer <access_token>
                                                              │
                                                              ▼
                                           JwtAuthenticationFilter: cabeçalho não é HS256 → SsoTokenAuthenticator
                                              • valida assinatura e issuer contra as chaves publicadas (JWKS)
                                              • lê sub, email, name, realm_access.roles
                                              • procura users.external_subject = sub
                                                  – existe → atualiza nome/e-mail, sincroniza papéis
                                                  – não existe, mas há conta LOCAL com o mesmo e-mail → liga-a ao SSO
                                                  – não existe → cria (ACTIVE), se SSO_AUTO_PROVISION=true
                                              • devolve o UserPrincipal local → o resto do sistema não sabe que foi SSO
```

A partir daí o token do Keycloak é usado exatamente como o local, em cada
pedido. Quando expira, o navegador volta à tela de login (o Keycloak costuma
entrar de novo sem pedir senha enquanto a sessão dele durar).

**Sair** termina também a sessão no Keycloak (`end_session_endpoint`), senão o
botão *Entrar com Keycloak* entrava de novo sem perguntar — o contrário do que
alguém que deixa um computador de bancada espera de "Sair".

---

## Variáveis

| Variável | Padrão | |
|---|---|---|
| `SSO_ENABLED` | `false` | Liga o SSO. |
| `SSO_ISSUER_URI` | — | `https://host/realms/<realm>`. Obrigatória. |
| `SSO_CLIENT_ID` | `lab-dms` | Cliente público do navegador. |
| `SSO_AUDIENCE` | — | Se definida, o token tem de a conter em `aud`. |
| `SSO_ROLES_CLAIM` | `realm_access.roles` | Caminho dos papéis no token. |
| `SSO_SYNC_ROLES` | `true` | O Keycloak manda nos papéis a cada entrada. Com `false`, só na criação. |
| `SSO_AUTO_PROVISION` | `true` | Cria a conta na primeira entrada. Com `false`, só entra quem já tiver conta ligada. |
| `SSO_DEFAULT_ROLES` | `ROLE_VIEWER` | Para tokens sem papel mapeado. |
| `SSO_GROUPS_CLAIM` | `groups` | Claim com os grupos do provedor (precisa do mapper acima). |
| `SSO_SYNC_GROUPS` | `true` | Espelha grupos em setores a cada entrada, quando o token traz a claim. |

---

## Contas

- `users.auth_provider` é `LOCAL` ou `KEYCLOAK`; `users.external_subject` é o
  `sub` do token, a chave estável (o e-mail pode mudar no provedor).
- Uma conta `KEYCLOAK` tem `password_hash = NULL`. O login local recusa-a com
  uma mensagem clara, e a tela *Senha e acesso* diz que a senha é gerida no
  provedor.
- Desativar a conta **no Lab DMS** continua a valer: um token válido de uma
  conta `INACTIVE` é recusado. Desativar no Keycloak também vale, porque o
  Keycloak deixa de emitir tokens.
- O administrador local continua a existir. É o caminho de volta se o Keycloak
  cair.

---

## Renovação do token

O *access token* do Keycloak dura minutos (5, por omissão). O navegador guarda
o *refresh token* da sessão e renova o acesso sozinho: um minuto antes de
expirar, e também quando um pedido volta com 401. Quem está a meio de uma
edição não vê a tela de login; só quando a própria sessão do Keycloak termina.
Os pedidos concorrentes partilham uma única renovação, porque o Keycloak roda
o *refresh token* a cada uso.

## O que ainda não faz

- **Logout iniciado pelo Keycloak** (*back-channel logout*). Sem estado de
  sessão no servidor, não há o que invalidar; os tokens são curtos por isso.
