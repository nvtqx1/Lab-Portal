import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';

import { clearAuthTokens, getStoredUser } from '../shared/api';
import { Button } from '../shared/components';

interface NavItem {
  label: string;
  path: string;
}

const adminNavItems: NavItem[] = [
  { label: 'Operational Logs', path: '/admin/operational-logs' },
  { label: 'AI Knowledge', path: '/admin/knowledge' },
  { label: 'Face Profiles', path: '/admin/face-profile' },
  { label: 'AI Assistant', path: '/admin/assistant' },
  { label: 'Notifications', path: '/admin/notifications' },
  { label: 'Dashboard', path: '/admin/dashboard' },
  { label: 'Users', path: '/admin/users' },
  { label: 'Labs', path: '/admin/labs' },
  { label: 'System Config', path: '/admin/system-config' },
  { label: 'Audit Logs', path: '/admin/audit-logs' },
];

export function AdminLayout() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const user = getStoredUser();
  const [isMobileNavOpen, setIsMobileNavOpen] = useState(false);

  const handleLogout = () => {
    clearAuthTokens();
    queryClient.clear();
    navigate('/login', { replace: true });
  };

  return (
    <div className="dark min-h-screen overflow-x-hidden bg-slate-950 text-slate-100">
      <a
        className="fixed left-4 top-3 z-toast -translate-y-20 rounded-lg bg-white px-4 py-3 text-sm font-semibold text-slate-950 shadow-lg transition-transform focus:translate-y-0"
        href="#admin-main-content"
      >
        Bỏ qua menu
      </a>
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 overflow-y-auto border-r border-slate-800 bg-slate-950 px-4 py-6 lg:block">
        <SidebarContent navItems={adminNavItems} onNavigate={() => setIsMobileNavOpen(false)} />
      </aside>

      {isMobileNavOpen ? (
        <div className="fixed inset-0 z-40 lg:hidden">
          <button
            aria-label="Đóng menu"
            className="absolute inset-0 bg-black/60"
            type="button"
            onClick={() => setIsMobileNavOpen(false)}
          />
          <aside className="relative h-full w-72 max-w-[85vw] overflow-y-auto bg-slate-950 px-4 py-5 shadow-xl">
            <div className="mb-5 flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="truncate text-base font-semibold text-white">Admin Portal</p>
                <p className="mt-1 text-xs font-medium text-slate-400">Quản trị viên</p>
              </div>
              <Button
                className="border-slate-700 bg-slate-900 text-slate-100 hover:bg-slate-800"
                size="sm"
                variant="outline"
                onClick={() => setIsMobileNavOpen(false)}
              >
                Đóng
              </Button>
            </div>
            <SidebarContent navItems={adminNavItems} compact onNavigate={() => setIsMobileNavOpen(false)} />
          </aside>
        </div>
      ) : null}

      <div className="min-w-0 lg:pl-64">
        <header className="sticky top-0 z-20 border-b border-slate-800 bg-slate-950/95 px-4 py-3 backdrop-blur lg:px-8">
          <div className="flex min-w-0 items-center justify-between gap-3">
            <div className="flex min-w-0 items-center gap-3">
              <Button
                aria-label="Mở menu"
                className="shrink-0 border-slate-700 bg-slate-900 text-slate-100 hover:bg-slate-800 lg:hidden"
                size="sm"
                variant="outline"
                onClick={() => setIsMobileNavOpen(true)}
              >
                Menu
              </Button>
              <div className="min-w-0">
                <p className="text-xs font-medium uppercase text-slate-400">Quản trị viên</p>
                <h1 className="truncate text-lg font-semibold text-white sm:text-xl">Admin Portal</h1>
              </div>
            </div>

            <div className="flex min-w-0 shrink-0 items-center justify-end gap-2 sm:gap-3">
              <div className="hidden min-w-0 items-center gap-3 rounded-md border border-slate-700 bg-slate-900 px-3 py-2 text-sm text-slate-300 sm:flex">
                <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-slate-700 text-xs font-semibold text-slate-100">
                  {(user?.fullName || user?.email || 'A').charAt(0).toUpperCase()}
                </span>
                <span className="min-w-0">
                  <span className="block max-w-44 truncate font-medium text-slate-100">
                    {user?.fullName || user?.email || 'Quản trị viên'}
                  </span>
                  <span className="block text-xs text-slate-400">Admin</span>
                </span>
              </div>
              <Button className="bg-white text-slate-950 hover:bg-slate-200" onClick={handleLogout} size="sm">
                Đăng xuất
              </Button>
            </div>
          </div>
        </header>

        <main className="min-w-0 max-w-full px-4 py-6 lg:px-8" id="admin-main-content" tabIndex={-1}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}

interface SidebarContentProps {
  navItems: NavItem[];
  compact?: boolean;
  onNavigate: () => void;
}

function SidebarContent({ navItems, compact = false, onNavigate }: SidebarContentProps) {
  return (
    <>
      {!compact ? (
        <>
          <div className="px-3 text-lg font-semibold tracking-tight text-white">Admin Portal</div>
          <div className="mt-3 rounded-md bg-slate-900 px-3 py-2 text-xs font-medium text-slate-300">
            Quản trị viên
          </div>
        </>
      ) : null}

      <nav className={compact ? 'space-y-1' : 'mt-8 space-y-1'}>
        {navItems.map((item) => (
          <NavLink
            key={item.path}
            to={item.path}
            className={({ isActive }) =>
              [
                  'flex min-h-11 items-center rounded-lg px-3 py-2 text-sm font-medium transition-colors duration-200',
                isActive
                  ? 'bg-white text-slate-950'
                  : 'text-slate-300 hover:bg-slate-800 hover:text-white',
              ].join(' ')
            }
            onClick={onNavigate}
          >
            {item.label}
          </NavLink>
        ))}
      </nav>
    </>
  );
}
