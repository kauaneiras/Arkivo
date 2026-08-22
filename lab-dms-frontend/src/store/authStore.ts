import { create } from 'zustand';

interface User {
  id: string;
  username: string;
  fullName: string;
  email: string;
  roles: string[];
}

interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  login: (token: string, user: User) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
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
}));

// Hydrate from localStorage on load
const token = localStorage.getItem('jwt_token');
const userInfo = localStorage.getItem('user_info');
if (token && userInfo) {
  useAuthStore.setState({ user: JSON.parse(userInfo), isAuthenticated: true });
}
