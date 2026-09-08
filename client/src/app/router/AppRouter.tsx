import { lazy, Suspense, type ReactNode } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';

import {
  AdminDashboardPage,
  AdminLabsPage,
  AdminSettingsPage,
  AdminUsersPage,
  AdminAuditLogPage,
} from '../../modules/admin/pages';
import {
  ForgotPasswordPage,
  LoginPage,
  RegisterPage,
  ResetPasswordPage,
  VerifyRegisterPage,
} from '../../modules/auth/pages';
import { LabSlotsPage, MyBookingsPage, SlotDetailPage } from '../../modules/booking/pages';
import {
  ApplicationListPage,
  CleaningPage,
  LabMembersPage,
  LabListPage,
  LabOverviewPage,
} from '../../modules/lab/pages';
import { ManagerComplaintsPage, PenaltyPage } from '../../modules/penalty/pages';
import { NotificationsPage } from '../../modules/notification/pages';
import { FaceProfilePage } from '../../modules/face/pages';
import { KnowledgePage } from '../../modules/knowledge/pages';
import { OperationalLogsPage } from '../../modules/operations/pages';
import { OtherPage, ProfilePage, StudentDashboardPage } from '../../modules/user/pages';
import { AdminLayout, AuthLayout, MainLayout } from '../../layouts';
import { getStoredRole } from '../../shared/api';
import { ForbiddenPage, LoadingState, NotFoundPage } from '../../shared/components';
import { ADMIN, LAB_MANAGER, STUDENT } from '../../shared/constants/roles';
import { ActiveMembershipRoute } from './ActiveMembershipRoute';
import { ProtectedRoute } from './ProtectedRoute';

const AssistantPage = lazy(() => import('../../modules/assistant/pages/AssistantPage')
  .then((module) => ({ default: module.AssistantPage })));
const CheckInPage = lazy(() => import('../../modules/booking/pages/CheckInPage')
  .then((module) => ({ default: module.CheckInPage })));
const ResearchPage = lazy(() => import('../../modules/research/pages/ResearchPage')
  .then((module) => ({ default: module.ResearchPage })));
const ResearchProjectDetailPage = lazy(() => import('../../modules/research/pages/ResearchProjectDetailPage')
  .then((module) => ({ default: module.ResearchProjectDetailPage })));
const ResearchGroupDetailPage = lazy(() => import('../../modules/research/pages/ResearchGroupDetailPage')
  .then((module) => ({ default: module.ResearchGroupDetailPage })));

function DeferredPage({ children }: { children: ReactNode }) {
  return <Suspense fallback={<LoadingState className="m-4">Đang mở màn hình…</LoadingState>}>{children}</Suspense>;
}

export function AppRouter() {
  return (
    <Routes>
      <Route element={<AuthLayout />}>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/register/verify" element={<VerifyRegisterPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
      </Route>

      <Route path="/403" element={<ForbiddenPage />} />
      <Route path="/404" element={<NotFoundPage />} />

      <Route element={<ProtectedRoute allowedRoles={[ADMIN]} />}>
        <Route path="/admin" element={<AdminLayout />}>
          <Route index element={<Navigate to="/admin/dashboard" replace />} />
          <Route path="dashboard" element={<AdminDashboardPage />} />
          <Route path="users" element={<AdminUsersPage />} />
          <Route path="labs" element={<AdminLabsPage />} />
          <Route path="system-config" element={<AdminSettingsPage />} />
          <Route path="settings" element={<Navigate to="/admin/system-config" replace />} />
          <Route path="audit-logs" element={<AdminAuditLogPage />} />
          <Route path="notifications" element={<NotificationsPage />} />
          <Route path="assistant" element={<DeferredPage><AssistantPage /></DeferredPage>} />
          <Route path="face-profile" element={<FaceProfilePage />} />
          <Route path="knowledge" element={<KnowledgePage />} />
          <Route path="operational-logs" element={<OperationalLogsPage />} />
        </Route>
      </Route>

      <Route element={<ProtectedRoute allowedRoles={[STUDENT, LAB_MANAGER]} />}>
        <Route path="/app" element={<MainLayout />}>
          <Route index element={<AppIndexRedirect />} />
          <Route path="profile" element={<ProfilePage />} />
          <Route path="notifications" element={<NotificationsPage />} />
          <Route path="assistant" element={<DeferredPage><AssistantPage /></DeferredPage>} />
          <Route path="face-profile" element={<FaceProfilePage />} />
        </Route>
      </Route>

      <Route element={<ProtectedRoute allowedRoles={[STUDENT]} />}>
        <Route path="/app" element={<MainLayout />}>
          <Route path="dashboard" element={<StudentDashboardPage />} />
          <Route path="labs" element={<LabListPage />} />
          <Route element={<ActiveMembershipRoute />}>
            <Route path="my-bookings" element={<MyBookingsPage />} />
            <Route path="penalties" element={<PenaltyPage />} />
            <Route path="other" element={<OtherPage />} />
          </Route>
        </Route>
      </Route>

      <Route element={<ProtectedRoute allowedRoles={[STUDENT, LAB_MANAGER]} />}>
        <Route path="/app" element={<MainLayout />}>
          <Route element={<ActiveMembershipRoute allowLabManager />}>
            <Route path="research" element={<DeferredPage><ResearchPage /></DeferredPage>} />
            <Route path="research/projects/:projectId" element={<DeferredPage><ResearchProjectDetailPage /></DeferredPage>} />
            <Route path="research/projects/:projectId/groups/:groupId" element={<DeferredPage><ResearchGroupDetailPage /></DeferredPage>} />
          </Route>
        </Route>
      </Route>

      <Route element={<ProtectedRoute allowedRoles={[LAB_MANAGER]} />}>
        <Route path="/app" element={<MainLayout />}>
          <Route path="applications" element={<ApplicationListPage />} />
          <Route path="lab-overview" element={<LabOverviewPage />} />
          <Route path="lab-info" element={<Navigate to="/app/lab-overview" replace />} />
          <Route path="lab-slots" element={<LabSlotsPage />} />
          <Route path="lab-slots/:slotId" element={<SlotDetailPage />} />
          <Route path="lab-bookings" element={<Navigate to="/app/lab-slots" replace />} />
          <Route path="lab-bokings" element={<Navigate to="/app/lab-slots" replace />} />
          <Route path="checkin-scan" element={<DeferredPage><CheckInPage /></DeferredPage>} />
          <Route path="lab-members" element={<LabMembersPage />} />
          <Route path="cleaning" element={<CleaningPage />} />
          <Route path="complaints" element={<ManagerComplaintsPage />} />
          <Route path="operational-logs" element={<OperationalLogsPage />} />
          <Route path="knowledge" element={<KnowledgePage />} />
        </Route>
      </Route>

      <Route path="/" element={<AppIndexRedirect />} />
      <Route path="*" element={<Navigate to="/404" replace />} />
    </Routes>
  );
}

function AppIndexRedirect() {
  const role = getStoredRole()?.replace(/^ROLE_/, '');
  return <Navigate to={role === LAB_MANAGER ? '/app/lab-overview' : '/app/dashboard'} replace />;
}
