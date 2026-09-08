import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';

import { ConfirmDialog, LoadingState } from '../../../shared/components';
import { getManagedLabId } from '../../../shared/utils/membership';
import { useCreatePenalty, useSlotPenalties } from '../../penalty/hooks';
import { formatPenaltyType } from '../../penalty/utils';
import { useCurrentUser } from '../../user/hooks';
import { CancelSlotModal, PenaltyCreateModal } from '../components';
import type { BookingResponse } from '../api';
import { useCompleteSlot, useReviewBooking, useSlot, useSlotRegistrations } from '../hooks';
import { getBookingStatusLabel, isUsableSlot } from '../utils';

function formatDateTime(value?: string) {
  if (!value) {
    return 'Chưa cập nhật';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return 'Chưa cập nhật';
  }
  return new Intl.DateTimeFormat('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

function canCreatePenalty(booking: BookingResponse) {
  return ['APPROVED', 'CHECKED_IN', 'CANCELLED_BY_STUDENT', 'NO_SHOW', 'COMPLETED'].includes(booking.status);
}

export function SlotDetailPage() {
  const { slotId } = useParams();
  const numericSlotId = Number(slotId);
  const [cancelSlotId, setCancelSlotId] = useState<number | null>(null);
  const [selectedBooking, setSelectedBooking] = useState<BookingResponse | null>(null);
  const [pendingConfirmation, setPendingConfirmation] = useState<
    | { type: 'REVIEW'; bookingId: number; decision: 'APPROVE' | 'REJECT' }
    | { type: 'COMPLETE' }
    | null
  >(null);
  const { data: currentUser } = useCurrentUser();
  const managedLabId = getManagedLabId(currentUser);
  const { data: slot, isLoading: isLoadingSlot } = useSlot(numericSlotId);
  const { data: registrations = [], isLoading, isError, refetch } = useSlotRegistrations(numericSlotId);
  const { data: slotPenalties = [] } = useSlotPenalties(numericSlotId);
  const reviewBooking = useReviewBooking(managedLabId, numericSlotId);
  const createPenalty = useCreatePenalty(numericSlotId);
  const completeSlot = useCompleteSlot(managedLabId, numericSlotId);
  const canMutateSlot = Boolean(slot && isUsableSlot(slot));
  const now = Date.now();
  const startTime = slot ? new Date(slot.startTime).getTime() : Number.NaN;
  const endTime = slot ? new Date(slot.endTime).getTime() : Number.NaN;
  const terminalSlot = Boolean(slot && ['CANCELLED', 'CLOSED', 'INACTIVE', 'ARCHIVED'].includes(slot.status));
  const canCancelSlot = Boolean(slot && canMutateSlot && !terminalSlot && now < startTime);
  const canCompleteSlot = Boolean(slot && !terminalSlot && startTime <= now && now < endTime);

  const counts = useMemo(
    () => ({
      pending: registrations.filter((booking) => booking.status === 'PENDING_APPROVAL').length,
      approved: registrations.filter((booking) => booking.status === 'APPROVED').length,
    }),
    [registrations],
  );

  const penaltiesByBookingId = useMemo(() => {
    const grouped = new Map<number, typeof slotPenalties>();
    slotPenalties.forEach((penalty) => {
      if (!penalty.bookingId) {
        return;
      }
      grouped.set(penalty.bookingId, [...(grouped.get(penalty.bookingId) ?? []), penalty]);
    });
    return grouped;
  }, [slotPenalties]);

  const handleReview = (bookingId: number, decision: 'APPROVE' | 'REJECT') => {
    setPendingConfirmation({ type: 'REVIEW', bookingId, decision });
  };

  const confirmPendingAction = () => {
    if (pendingConfirmation?.type === 'REVIEW') {
      reviewBooking.mutate(
        { bookingId: pendingConfirmation.bookingId, decision: pendingConfirmation.decision },
        { onSuccess: () => setPendingConfirmation(null) },
      );
    } else if (pendingConfirmation?.type === 'COMPLETE' && slot) {
      completeSlot.mutate(slot.id, { onSuccess: () => setPendingConfirmation(null) });
    }
  };

  const handleCreatePenalty = (payload: {
    userId: number;
    slotId: number;
    bookingId: number;
    type: string;
    point: number;
    reason: string;
  }) => {
    createPenalty.mutate(payload, {
      onSuccess: () => setSelectedBooking(null),
    });
  };

  return (
    <section className="space-y-6">
      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <Link className="text-sm font-semibold text-slate-600 hover:text-slate-950" to="/app/lab-slots">
          Quay lại danh sách
        </Link>
        <div className="mt-4 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div>
          <h1 className="text-xl font-semibold text-slate-950">Chi tiết ca sử dụng</h1>
            <p className="mt-2 text-sm text-slate-600">
              Danh sách sinh viên đăng ký sử dụng khung giờ và vi phạm đã ghi nhận.
            </p>
          </div>
          {canCancelSlot && slot ? (
            <button
              type="button"
              className="w-fit rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm font-semibold text-red-700"
              onClick={() => setCancelSlotId(slot.id)}
            >
              Hủy khung giờ
            </button>
          ) : canCompleteSlot && slot ? (
            <button
              type="button"
              className="w-fit rounded-md bg-red-700 px-3 py-2 text-sm font-semibold text-white disabled:opacity-60"
              disabled={completeSlot.isPending}
              onClick={() => setPendingConfirmation({ type: 'COMPLETE' })}
            >
              {completeSlot.isPending ? 'Đang kết thúc...' : 'Kết thúc ca lab'}
            </button>
          ) : null}
        </div>
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        {isLoadingSlot ? (
          <LoadingState />
        ) : slot ? (
          <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <dt className="text-sm text-slate-500">Thời gian</dt>
              <dd className="mt-1 text-sm font-semibold text-slate-950">
                {formatDateTime(slot.startTime)} - {formatDateTime(slot.endTime)}
              </dd>
            </div>
            <div>
              <dt className="text-sm text-slate-500">Sức chứa</dt>
              <dd className="mt-1 text-sm font-semibold text-slate-950">{slot.capacity}</dd>
            </div>
            <div>
              <dt className="text-sm text-slate-500">Chờ phê duyệt</dt>
              <dd className="mt-1 text-sm font-semibold text-slate-950">{counts.pending}</dd>
            </div>
            <div>
              <dt className="text-sm text-slate-500">Đã phê duyệt</dt>
              <dd className="mt-1 text-sm font-semibold text-slate-950">{counts.approved}</dd>
            </div>
          </dl>
        ) : (
          <p className="text-sm text-slate-600">Không tìm thấy khung giờ sử dụng.</p>
        )}
      </div>

      <div className="rounded-lg border border-slate-200 bg-white shadow-sm">
        <div className="border-b border-slate-200 px-5 py-4">
          <h3 className="text-lg font-semibold text-slate-950">Danh sách đăng ký</h3>
        </div>
        {isLoading ? (
          <div className="p-5 text-sm text-slate-600">Đang tải danh sách đăng ký...</div>
        ) : isError ? (
          <div className="p-5 text-sm text-red-700">
            Không thể tải danh sách đăng ký.
            <button className="ml-3 font-semibold underline" type="button" onClick={() => refetch()}>
              Tải lại
            </button>
          </div>
        ) : registrations.length ? (
          <div className="max-w-full overscroll-x-contain overflow-x-auto">
            <table className="w-full min-w-[720px] divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50 text-left text-slate-500">
                <tr>
                  <th className="px-5 py-3 font-semibold">Sinh viên</th>
                  <th className="px-5 py-3 font-semibold">Email</th>
                  <th className="px-5 py-3 font-semibold">Thời gian đăng ký</th>
                  <th className="px-5 py-3 font-semibold">Trạng thái</th>
                  <th className="px-5 py-3 font-semibold">Vi phạm đã ghi nhận</th>
                  <th className="px-5 py-3 font-semibold">Thao tác</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200">
                {registrations.map((booking) => {
                  const penalties = penaltiesByBookingId.get(booking.id) ?? [];

                  return (
                    <tr key={booking.id}>
                      <td className="px-5 py-3 font-medium text-slate-950">
                        {booking.studentName || 'Chưa cập nhật'}
                      </td>
                      <td className="px-5 py-3 text-slate-600">{booking.studentEmail || 'Chưa cập nhật'}</td>
                      <td className="px-5 py-3 text-slate-600">{formatDateTime(booking.createdAt)}</td>
                      <td className="px-5 py-3 text-slate-700">{getBookingStatusLabel(booking.status)}</td>
                      <td className="px-5 py-3">
                        {penalties.length ? (
                          <div className="flex flex-wrap gap-1.5">
                            {penalties.map((penalty) => (
                              <span
                                key={penalty.id}
                                className="rounded-full bg-amber-50 px-2 py-1 text-xs font-semibold text-amber-700 ring-1 ring-amber-200"
                                title={penalty.reason}
                              >
                                {formatPenaltyType(penalty.type)}
                              </span>
                            ))}
                          </div>
                        ) : (
                          <span className="text-slate-500">-</span>
                        )}
                      </td>
                      <td className="px-5 py-3">
                        <div className="flex flex-wrap gap-2">
                          {booking.status === 'PENDING_APPROVAL' && canMutateSlot ? (
                            <>
                              <button
                                type="button"
                                className="rounded-md bg-emerald-600 px-3 py-1.5 text-xs font-semibold text-white"
                                disabled={reviewBooking.isPending}
                                onClick={() => handleReview(booking.id, 'APPROVE')}
                              >
                                Phê duyệt
                              </button>
                              <button
                                type="button"
                                className="rounded-md bg-red-600 px-3 py-1.5 text-xs font-semibold text-white"
                                disabled={reviewBooking.isPending}
                                onClick={() => handleReview(booking.id, 'REJECT')}
                              >
                                Từ chối
                              </button>
                            </>
                          ) : null}

                          {canMutateSlot && canCreatePenalty(booking) ? (
                            <button
                              type="button"
                              className="rounded-md border border-amber-200 bg-amber-50 px-3 py-1.5 text-xs font-semibold text-amber-700"
                              onClick={() => setSelectedBooking(booking)}
                            >
                              Ghi nhận vi phạm
                            </button>
                          ) : null}

                          {(!canMutateSlot || (booking.status !== 'PENDING_APPROVAL' && !canCreatePenalty(booking))) ? (
                            <span className="text-slate-500">Không có thao tác</span>
                          ) : null}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="p-5 text-sm text-slate-600">Chưa có sinh viên đăng ký khung giờ này.</div>
        )}
      </div>

      {managedLabId ? (
        <CancelSlotModal
          labId={managedLabId}
          slotId={cancelSlotId}
          isOpen={Boolean(cancelSlotId)}
          onClose={() => setCancelSlotId(null)}
        />
      ) : null}

      <PenaltyCreateModal
        booking={selectedBooking}
        slot={slot}
        isOpen={Boolean(selectedBooking)}
        isSubmitting={createPenalty.isPending}
        onClose={() => setSelectedBooking(null)}
        onSubmit={handleCreatePenalty}
      />
      <ConfirmDialog
        confirmLabel={pendingConfirmation?.type === 'COMPLETE'
          ? 'Kết thúc ca'
          : pendingConfirmation?.decision === 'APPROVE' ? 'Phê duyệt' : 'Từ chối'}
        isOpen={pendingConfirmation !== null}
        isPending={reviewBooking.isPending || completeSlot.isPending}
        message={pendingConfirmation?.type === 'COMPLETE'
          ? 'Bạn có chắc muốn kết thúc ca sử dụng Lab ngay bây giờ không?'
          : pendingConfirmation?.decision === 'APPROVE'
            ? 'Phê duyệt đăng ký sử dụng PTN này?'
            : 'Từ chối đăng ký sử dụng PTN này?'}
        onCancel={() => setPendingConfirmation(null)}
        onConfirm={confirmPendingAction}
        title={pendingConfirmation?.type === 'COMPLETE'
          ? 'Kết thúc ca sử dụng?'
          : pendingConfirmation?.decision === 'APPROVE' ? 'Phê duyệt đăng ký?' : 'Từ chối đăng ký?'}
        variant={pendingConfirmation?.type === 'REVIEW' && pendingConfirmation.decision === 'APPROVE'
          ? 'success'
          : 'danger'}
      />
    </section>
  );
}
