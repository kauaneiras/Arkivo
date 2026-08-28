import { create } from 'zustand';

interface User {
  id: string;
  username: string;
  fullName: string;
  email: string;
  /**
   * Papéis E permissões, juntos.
   *
   * O servidor devolve as *authorities* do Spring Security, que são a união das
   * duas coisas: 'ROLE_ADMIN' ao lado de 'DOCUMENT_CREATE'. O nome do campo é
   * herdado e ficou mais estreito do que o conteúdo — daí o `can()` abaixo, que
   * é por onde as telas devem perguntar.
   */
  roles: string[];
}

interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  login: (token: string, user: User) => void;
  logout: () => void;
  /** Esta pessoa pode fazer isto? Uma pergunta, um sítio. */
  can: (permission: string) => boolean;
  /** Relê identidade e permissões do servidor. */
  refresh: () => Promise<void>;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  isAuthenticated: false,
  login: (token, user) => {
    localStorage.setItem('jwt_token', token);
    localStorage.setItem('user_info', JSON.stringify(user));
    set({ user, isAuthenticated: true });
  },
  logout: () => {
    localStorage.removeItem('jwt_token');
    localStorage.removeItem('user_info');
    set({ user: null, isAuthenticated: false });
  },

  /**
   * A regra vive aqui e não em cada ecrã.
   *
   * Espalhar `roles.includes(...)` pelas páginas garante que uma delas fica para
   * trás quando a regra muda, e uma tela que se engana a mais mostra um botão
   * que o servidor vai recusar. Ninguém precisa de listar o administrador como
   * exceção: ele recebe todas as permissões, por isso já cai na verificação
   * normal.
   */
  can: (permission) => {
    const user = get().user;
    if (!user?.roles) return false;
    return user.roles.includes(permission);
  },

  refresh: async () => {
    // Import dinâmico: o cliente HTTP importa este store, e importá-lo de volta
    // no topo fecharia um ciclo.
    const { default: api } = await import('../lib/api');
    try {
      const res = await api.get('/auth/me');
      const user: User = res.data;
      localStorage.setItem('user_info', JSON.stringify(user));
      set({ user, isAuthenticated: true });
    } catch {
      // Sessão inválida já é tratada pelo interceptor do cliente HTTP; aqui
      // simplesmente ficamos com o que estava guardado.
    }
  },
}));

// Hydrate from localStorage on load
const token = localStorage.getItem('jwt_token');
const userInfo = localStorage.getItem('user_info');
if (token && userInfo) {
  useAuthStore.setState({ user: JSON.parse(userInfo), isAuthenticated: true });
  // O que está guardado pode ser de antes de uma permissão nova existir. Isto
  // corrige a sessão em vez de obrigar a sair e voltar a entrar.
  void useAuthStore.getState().refresh();
}
