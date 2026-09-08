import { useMemo, useState } from 'react';

import { Button, ConfirmDialog, EmptyState, ErrorState, LoadingState, Modal, ResponsiveTable } from '../../../shared/components';
import { getManagedLabId, getManagedLabName } from '../../../shared/utils/membership';
import { useCurrentUser } from '../../user/hooks';
import { useLabMembers, useRemoveLabMember } from '../hooks';

function statusClassName(status: string) {
  if (status.toUpperCase() === 'ACTIVE') {
    return 'bg-emerald-50 text-emerald-700 ring-emerald-200';
  }

  return 'bg-slate-100 text-slate-600 ring-slate-200';
}

function formatDate(value?: string) {
  return value ? new Intl.DateTimeFormat('vi-VN', { dateStyle: 'short' }).format(new Date(value)) : 'Chưa cập nhật';
}

function formatRole(role?: string) {
  return role === 'LAB_MANAGER' ? 'Quản lý PTN' : role === 'STUDENT' || role === 'MEMBER' ? 'Sinh viên' : 'Thành viên';
}

function formatStatus(status: string) {
  return status.toUpperCase() === 'ACTIVE' ? 'Đang hoạt động' : 'Không hoạt động';
}

export function LabMembersPage() {
  const { data: currentUser, isLoading: isLoadingUser } = useCurrentUser();
  const managedLabId = getManagedLabId(currentUser);
  const managedLabName = getManagedLabName(currentUser);
  const { data: members = [], isLoading, isError } = useLabMembers(managedLabId);
  const removeMemberMutation = useRemoveLabMember();
  const [search, setSearch] = useState('');
  const [memberPendingRemove, setMemberPendingRemove] = useState<(typeof members)[number] | null>(null);
  const [selectedMemberDetail, setSelectedMemberDetail] = useState<(typeof members)[number] | null>(null);

  const handleRemoveMember = (member: (typeof members)[number]) => {
    if (!managedLabId) {
      return;
    }
    setMemberPendingRemove(member);
  };

  const confirmRemoveMember = () => {
    if (!managedLabId || !memberPendingRemove) return;
    removeMemberMutation.mutate(
      { labId: managedLabId, userId: memberPendingRemove.userId },
      { onSuccess: () => setMemberPendingRemove(null) },
    );
  };

  const filteredMembers = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    return members.filter((member) => {
      const isActive = member.status.toUpperCase() === 'ACTIVE';
      const matchesSearch =
        !keyword ||
        member.fullName?.toLowerCase().includes(keyword) ||
        member.email.toLowerCase().includes(keyword);
      return isActive && matchesSearch;
    });
  }, [members, search]);

  if (isLoadingUser || isLoading) {
    return (
      <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <LoadingState />
      </section>
    );
  }

  if (!managedLabId) {
    return (
      <section className="rounded-lg border border-amber-200 bg-white p-6 text-sm text-amber-700 shadow-sm">
        Bạn chưa được phân công quản lý PTN nào.
      </section>
    );
  }

  if (isError) {
    return (
      <section className="rounded-lg border border-red-200 bg-white p-6 shadow-sm">
        <ErrorState />
      </section>
    );
  }

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-xl font-semibold text-slate-950">Thành viên PTN</h1>
          <p className="mt-1 text-sm text-slate-600">
            Danh sách thành viên thuộc PTN bạn quản lý
            {managedLabName ? `: ${managedLabName}` : ''}.
          </p>
        </div>
        <input
          className="rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-950 outline-none transition placeholder:text-slate-400 focus:border-slate-900 focus:ring-2 focus:ring-slate-900/10"
          placeholder="Tìm theo tên hoặc email"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
      </div>

      {filteredMembers.length === 0 ? (
        <EmptyState className="mt-6">Chưa có thành viên đang hoạt động trong PTN này.</EmptyState>
      ) : (
        <ResponsiveTable className="mt-6">
          <table className="w-full min-w-[680px] divide-y divide-slate-200 text-sm">
            <thead>
              <tr className="text-left text-xs font-semibold uppercase text-slate-500">
                <th className="px-3 py-3">Họ tên</th>
                <th className="px-3 py-3">Email</th>
                <th className="px-3 py-3">Vai trò</th>
                <th className="px-3 py-3">Trạng thái</th>
                <th className="px-3 py-3">Ngày tham gia</th>
                <th className="px-3 py-3 text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {filteredMembers.map((member) => (
                <tr key={member.id}>
                  <td className="px-3 py-4 font-medium text-slate-950">
                    {member.fullName || 'Chưa cập nhật'}
                  </td>
                  <td className="px-3 py-4 text-slate-700">{member.email}</td>
                  <td className="px-3 py-4 text-slate-700">{formatRole(member.role)}</td>
                  <td className="px-3 py-4">
                    <span
                      className={[
                        'inline-flex rounded-full px-2 py-1 text-xs font-semibold ring-1',
                        statusClassName(member.status),
                      ].join(' ')}
                    >
                      {formatStatus(member.status)}
                    </span>
                  </td>
                  <td className="px-3 py-4 text-slate-700">{formatDate(member.joinedAt)}</td>
                  <td className="px-3 py-4 text-right">
                    <div className="flex justify-end gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => setSelectedMemberDetail(member)}
                    >
                      Xem chi tiết
                    </Button>
                    <Button
                      loading={removeMemberMutation.isPending}
                      loadingText="Đang gỡ..."
                      size="sm"
                      variant="danger"
                      onClick={() => handleRemoveMember(member)}
                    >
                      Gỡ thành viên
                    </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ResponsiveTable>
      )}
      <ConfirmDialog
        confirmLabel="Gỡ thành viên"
        isOpen={memberPendingRemove !== null}
        isPending={removeMemberMutation.isPending}
        message="Tài khoản vẫn được giữ lại; hệ thống chỉ gỡ tư cách thành viên trong PTN."
        onCancel={() => setMemberPendingRemove(null)}
        onConfirm={confirmRemoveMember}
        title="Gỡ thành viên khỏi PTN?"
      />
      <Modal
        centered
        closeOnEscape
        isOpen={selectedMemberDetail !== null}
        onClose={() => setSelectedMemberDetail(null)}
        size="sm"
        title="Chi tiết thành viên"
      >
        <dl className="space-y-3 text-sm">
          <div><dt className="text-slate-500">Họ tên</dt><dd className="font-semibold text-slate-950">{selectedMemberDetail?.fullName || 'Chưa cập nhật'}</dd></div>
          <div><dt className="text-slate-500">Email</dt><dd className="text-slate-800">{selectedMemberDetail?.email}</dd></div>
        </dl>
      </Modal>
    </section>
  );
}
