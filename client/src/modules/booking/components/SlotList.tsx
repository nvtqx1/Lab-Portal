import { useNavigate } from 'react-router-dom';
import { useState } from 'react';

import { ConfirmDialog } from '../../../shared/components';
import type { BookingResponse } from '../api';
import { useCancelBooking, useCreateBooking, useMyBookings } from '../hooks';
import { useLabSlots } from '../hooks';
import { isUsableSlot } from '../utils';
import { SlotCard } from './SlotCard';

interface SlotListProps {
  labId?: number | null;
  readonly?: boolean;
  canCreate?: boolean;
  mode?: 'readonly' | 'student' | 'manager';
  showLabName?: boolean;
  onCancelSlot?: (slotId: number) => void;
  view?: 'active' | 'history';
}

function findActiveBookingForSlot(bookings: BookingResponse[], slotId: number) {
  return (
    bookings.find(
      (booking) =>
        booking.slotId === slotId &&
        !['REJECTED', 'CANCELLED_BY_STUDENT', 'CANCELLED_BY_MANAGER', 'CANCELLED'].includes(
          booking.status,
        ),
    ) ?? null
  );
}

export function SlotList({
  labId,
  mode = 'readonly',
  showLabName = false,
  onCancelSlot,
  view = 'active',
}: SlotListProps) {
  const navigate = useNavigate();
  const { data: slots = [], isError, isLoading, isFetching, refetch } = useLabSlots(labId, view);
  const visibleSlots = view === 'history' ? slots : slots.filter(isUsableSlot);
  const { data: myBookings = [] } = useMyBookings(mode === 'student');
  const createBooking = useCreateBooking(labId);
  const cancelBooking = useCancelBooking(labId);
  const [bookingPendingCancel, setBookingPendingCancel] = useState<number | null>(null);

  if (!labId) {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-6 text-sm text-slate-600 shadow-sm">
        Chưa chọn phòng thí nghiệm để xem khung giờ sử dụng.
      </div>
    );
  }

  if (isLoading) {
    return (
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {[1, 2, 3].map((item) => (
          <div
            key={item}
            className="h-44 animate-pulse rounded-lg border border-slate-200 bg-white p-5 shadow-sm"
          >
            <div className="h-4 w-24 rounded bg-slate-200" />
            <div className="mt-3 h-6 w-36 rounded bg-slate-200" />
            <div className="mt-8 grid grid-cols-2 gap-4">
              <div className="h-10 rounded bg-slate-100" />
              <div className="h-10 rounded bg-slate-100" />
            </div>
          </div>
        ))}
      </div>
    );
  }

  if (isError) {
    return (
      <div className="rounded-lg border border-red-200 bg-white p-6 text-sm text-red-700 shadow-sm">
        <p>Không thể tải danh sách khung giờ sử dụng.</p>
        <button
          type="button"
          className="mt-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm font-semibold text-red-700 transition hover:bg-red-100"
          onClick={() => refetch()}
        >
          Tải lại
        </button>
      </div>
    );
  }

  if (!visibleSlots.length) {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-6 text-sm text-slate-600 shadow-sm">
        {view === 'history'
          ? 'Chưa có lịch sử ca sử dụng.'
          : 'Hiện chưa có khung giờ sử dụng nào còn hiệu lực.'}
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {isFetching ? (
        <p className="text-xs font-medium text-slate-500">
          Đang cập nhật khung giờ sử dụng...
        </p>
      ) : null}
      <div className={mode === 'manager' ? 'grid gap-4 lg:grid-cols-2' : 'grid gap-4 md:grid-cols-2 xl:grid-cols-3'}>
        {visibleSlots.map((slot) => (
          <SlotCard
            key={slot.id}
            slot={slot}
            showLabName={showLabName}
            mode={mode}
            userBooking={mode === 'student' ? findActiveBookingForSlot(myBookings, slot.id) : null}
            isMutating={createBooking.isPending || cancelBooking.isPending}
            onRegister={(selectedSlot) => createBooking.mutate(selectedSlot.id)}
            onCancelBooking={(booking) => setBookingPendingCancel(booking.id)}
            onViewDetail={(selectedSlot) => navigate(`/app/lab-slots/${selectedSlot.id}`)}
            onCancelSlot={(selectedSlot) => onCancelSlot?.(selectedSlot.id)}
            historical={view === 'history'}
          />
        ))}
      </div>
      <ConfirmDialog
        confirmLabel="Hủy đăng ký"
        isOpen={bookingPendingCancel !== null}
        isPending={cancelBooking.isPending}
        message="Bạn có chắc muốn hủy đăng ký sử dụng khung giờ này không?"
        onCancel={() => setBookingPendingCancel(null)}
        onConfirm={() => {
          if (bookingPendingCancel === null) return;
          cancelBooking.mutate(bookingPendingCancel, { onSuccess: () => setBookingPendingCancel(null) });
        }}
        title="Hủy đăng ký sử dụng?"
      />
    </div>
  );
}
