import axios from 'axios';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { queryKeys } from '../../../shared/api';
import { toast } from '../../../shared/components';
import {
  cancelSlot,
  completeSlot,
  createSlot,
  getLabSlotHistory,
  getLabSlots,
  getSlot,
  type CancelSlotPayload,
  type CreateSlotPayload,
} from '../api';
import { isUsableSlot, normalizeSlot } from '../utils';

export function useLabSlots(labId?: number | null, view: 'active' | 'history' = 'active') {
  return useQuery({
    queryKey: view === 'history'
      ? queryKeys.slots.history(labId as number)
      : queryKeys.slots.byLab(labId as number),
    queryFn: async () => {
      const slots = view === 'history'
        ? await getLabSlotHistory(labId as number)
        : await getLabSlots(labId as number);
      const normalized = slots.map(normalizeSlot);
      return view === 'history' ? normalized : normalized.filter(isUsableSlot);
    },
    enabled: Boolean(labId),
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
    refetchInterval: 60000,
    staleTime: 30000,
  });
}

export function useSlot(slotId?: number | null) {
  return useQuery({
    queryKey: queryKeys.slots.detail(slotId as number),
    queryFn: async () => normalizeSlot(await getSlot(slotId as number)),
    enabled: Boolean(slotId),
    staleTime: 30000,
    refetchInterval: 30000,
  });
}

export function useCompleteSlot(labId?: number | null, slotId?: number | null) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => completeSlot(id),
    onSuccess: () => {
      if (labId) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.slots.byLab(labId) });
        void queryClient.invalidateQueries({ queryKey: queryKeys.cleaning.overview(labId) });
      }
      void queryClient.invalidateQueries({ queryKey: queryKeys.slots.detail(slotId as number) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.slots.bookings(slotId as number) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.bookings.mine });
      toast.success('Đã kết thúc ca sử dụng lab.');
    },
    onError: (error) => toast.error(getErrorMessage(error, 'Không thể kết thúc ca sử dụng lab.')),
  });
}

function getErrorMessage(error: unknown, fallback = 'Không thể tạo khung giờ sử dụng.') {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as { message?: string; errors?: string[] } | undefined;
    const message = data?.message ?? data?.errors?.[0];
    if (message === 'Time slot overlaps with an existing slot in this lab') {
      return 'Khung giờ này bị trùng với một ca sử dụng đang hoạt động. Vui lòng chọn thời gian khác.';
    }
    return message ?? fallback;
  }

  return fallback;
}

export function useCreateSlot() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: CreateSlotPayload) => createSlot(payload),
    onSuccess: (_slot, payload) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.slots.byLab(payload.labId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.cleaning.overview(payload.labId) });
      toast.success('Đã tạo khung giờ sử dụng thành công.');
    },
    onError: (error) => {
      toast.error(getErrorMessage(error));
    },
  });
}

export function useCancelSlot(labId?: number | null, slotId?: number | null) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: CancelSlotPayload) => cancelSlot(payload),
    onSuccess: (_slot, payload) => {
      if (labId) {
        queryClient.invalidateQueries({ queryKey: queryKeys.slots.byLab(labId) });
        queryClient.invalidateQueries({ queryKey: queryKeys.cleaning.overview(labId) });
      }
      queryClient.invalidateQueries({ queryKey: queryKeys.slots.detail(slotId ?? payload.slotId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.slots.bookings(slotId ?? payload.slotId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.bookings.mine });
      toast.success('Đã hủy khung giờ sử dụng.');
    },
    onError: (error) => {
      toast.error(getErrorMessage(error, 'Không thể hủy khung giờ sử dụng.'));
    },
  });
}
