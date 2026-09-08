import { useState } from 'react';

import { resolveApiAssetUrl } from '../../../shared/api';
import { ConfirmDialog } from '../../../shared/components';
import { getManagedLabId, getManagedLabName } from '../../../shared/utils/membership';
import { useCurrentUser } from '../../user/hooks';
import type { ApplicationStatus } from '../api';
import { useApplications, useReviewApplication } from '../hooks';

function statusClassName(status: ApplicationStatus) {
  if (status === 'APPROVED') {
    return 'bg-emerald-50 text-emerald-700 ring-emerald-200';
  }

  if (status === 'REJECTED') {
    return 'bg-red-50 text-red-700 ring-red-200';
  }

  return 'bg-amber-50 text-amber-700 ring-amber-200';
}

function formatStatus(status: ApplicationStatus) {
  return status === 'APPROVED' ? 'Đã duyệt' : status === 'REJECTED' ? 'Đã từ chối' : 'Chờ duyệt';
}

function formatDate(value: string) {
  if (!value) {
    return 'Chưa cập nhật';
  }

  return new Intl.DateTimeFormat('vi-VN', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(value));
}

export function ApplicationList() {
  const { data: currentUser, isLoading: isLoadingUser } = useCurrentUser();
  const managedLabId = getManagedLabId(currentUser);
  const managedLabName = getManagedLabName(currentUser);
  const { data: applications = [], isLoading, isError } = useApplications(managedLabId);
  const reviewMutation = useReviewApplication(managedLabId);
  const [processingId, setProcessingId] = useState<number | null>(null);
  const [pendingReview, setPendingReview] = useState<{
    appId: number;
    status: Extract<ApplicationStatus, 'APPROVED' | 'REJECTED'>;
  } | null>(null);

  const handleReview = (
    appId: number,
    status: Extract<ApplicationStatus, 'APPROVED' | 'REJECTED'>,
  ) => {
    setPendingReview({ appId, status });
  };

  const confirmReview = async () => {
    if (!pendingReview) return;
    setProcessingId(pendingReview.appId);
    try {
      await reviewMutation.mutateAsync(pendingReview);
      setPendingReview(null);
    } finally {
      setProcessingId(null);
    }
  };

  if (isLoadingUser || isLoading) {
    return (
      <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <div className="h-6 w-48 animate-pulse rounded bg-slate-200" />
        <div className="mt-6 space-y-3">
          {Array.from({ length: 4 }).map((_, index) => (
            <div key={index} className="h-10 animate-pulse rounded bg-slate-100" />
          ))}
        </div>
      </section>
    );
  }

  if (!managedLabId) {
    return (
      <section className="rounded-lg border border-amber-200 bg-white p-6 text-sm text-amber-700 shadow-sm">
        Tài khoản quản lý hiện chưa được phân công PTN.
      </section>
    );
  }

  if (isError) {
    return (
      <section className="rounded-lg border border-red-200 bg-white p-6 text-sm text-red-700 shadow-sm">
        Không thể tải danh sách đơn ứng tuyển của PTN đang quản lý.
      </section>
    );
  }

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold text-slate-950">Đơn ứng tuyển</h1>
          <p className="mt-1 text-sm text-slate-600">
            Chỉ hiển thị đơn ứng tuyển của {managedLabName ?? `PTN #${managedLabId}`}.
          </p>
        </div>
        <span className="shrink-0 text-sm text-slate-500">
          {applications.length} đơn
        </span>
      </div>

      {applications.length === 0 ? (
        <div className="mt-6 rounded-md border border-dashed border-slate-300 p-8 text-center text-sm text-slate-600">
          Hiện chưa có đơn ứng tuyển nào.
        </div>
      ) : (
        <div className="mt-6 max-w-full overscroll-x-contain overflow-x-auto">
          <table className="w-full min-w-[760px] divide-y divide-slate-200 text-sm">
            <thead>
              <tr className="text-left text-xs font-semibold uppercase text-slate-500">
                <th className="px-3 py-3">Ứng viên</th>
                <th className="px-3 py-3">Email</th>
                <th className="px-3 py-3">PTN</th>
                <th className="px-3 py-3">CV</th>
                <th className="px-3 py-3">Trạng thái</th>
                <th className="px-3 py-3">Ngày tạo</th>
                <th className="px-3 py-3 text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {applications.map((application) => {
                const isProcessing =
                  reviewMutation.isPending && processingId === application.id;
                const disableActions = reviewMutation.isPending;

                return (
                  <tr key={application.id}>
                    <td className="px-3 py-4 text-slate-700">
                      <div className="font-medium text-slate-950">
                        {application.applicantName || `Người dùng #${application.userId}`}
                      </div>
                    </td>
                    <td className="px-3 py-4 text-slate-700">
                      {application.applicantEmail}
                    </td>
                    <td className="px-3 py-4 text-slate-700">
                      {application.labName}
                    </td>
                    <td className="px-3 py-4">
                      {application.cvUrl || application.cvFileUrl ? (
                        <div className="flex flex-col gap-1">
                          {application.cvUrl ? (
                            <a
                              className="whitespace-nowrap font-medium text-slate-950 underline-offset-2 hover:underline"
                              href={application.cvUrl}
                              rel="noreferrer"
                              target="_blank"
                            >
                              Xem CV URL
                            </a>
                          ) : null}
                          {application.cvFileUrl ? (
                            <a
                              className="whitespace-nowrap font-medium text-slate-950 underline-offset-2 hover:underline"
                              href={resolveApiAssetUrl(application.cvFileUrl)}
                              rel="noreferrer"
                              target="_blank"
                              title={application.cvFileName ?? 'Tệp CV'}
                            >
                              Tải file CV
                            </a>
                          ) : null}
                        </div>
                      ) : (
                        <span className="text-slate-400">-</span>
                      )}
                    </td>
                    <td className="px-3 py-4">
                      <span
                        className={[
                          'inline-flex rounded-full px-2 py-1 text-xs font-semibold ring-1',
                          statusClassName(application.status),
                        ].join(' ')}
                      >
                        {formatStatus(application.status)}
                      </span>
                    </td>
                    <td className="px-3 py-4 text-slate-700">
                      {formatDate(application.createdAt)}
                    </td>
                    <td className="px-3 py-4 text-right">
                      {application.status === 'PENDING' ? (
                        <div className="flex justify-end gap-2">
                          <button
                            type="button"
                            disabled={disableActions}
                            className="rounded-md bg-emerald-600 px-3 py-1.5 text-xs font-semibold text-white transition hover:bg-emerald-700 disabled:cursor-not-allowed disabled:bg-emerald-300"
                            onClick={() => handleReview(application.id, 'APPROVED')}
                          >
                            {isProcessing ? 'Đang xử lý...' : 'Duyệt'}
                          </button>
                          <button
                            type="button"
                            disabled={disableActions}
                            className="rounded-md bg-red-600 px-3 py-1.5 text-xs font-semibold text-white transition hover:bg-red-700 disabled:cursor-not-allowed disabled:bg-red-300"
                            onClick={() => handleReview(application.id, 'REJECTED')}
                          >
                            Từ chối
                          </button>
                        </div>
                      ) : (
                        <span className="text-xs text-slate-400">Đã xử lý</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <ConfirmDialog
        confirmLabel={pendingReview?.status === 'APPROVED' ? 'Duyệt đơn' : 'Từ chối đơn'}
        isOpen={pendingReview !== null}
        isPending={reviewMutation.isPending}
        message={pendingReview?.status === 'APPROVED'
          ? 'Bạn chắc chắn muốn duyệt đơn này?'
          : 'Bạn chắc chắn muốn từ chối đơn này?'}
        onCancel={() => setPendingReview(null)}
        onConfirm={() => { void confirmReview(); }}
        title={pendingReview?.status === 'APPROVED' ? 'Duyệt đơn ứng tuyển?' : 'Từ chối đơn ứng tuyển?'}
        variant={pendingReview?.status === 'APPROVED' ? 'success' : 'danger'}
      />
    </section>
  );
}
