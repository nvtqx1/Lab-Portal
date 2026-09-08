import { apiClient } from '../../../shared/api';
import type { Response } from '../../../shared/types';

export interface RawSlotResponse {
  id?: number;
  labId?: number;
  lab_id?: number;
  lab?: {
    id?: number;
    name?: string;
    labName?: string;
  };
  labName?: string;
  lab_name?: string;
  startTime?: string;
  start_time?: string;
  endTime?: string;
  end_time?: string;
  capacity?: number;
  bookedCount?: number;
  booked_count?: number;
  approvedCount?: number;
  approved_count?: number;
  checkedInCount?: number;
  checked_in_count?: number;
  pendingCount?: number;
  pending_count?: number;
  currentBookings?: number;
  current_bookings?: number;
  remainingCapacity?: number;
  remaining_capacity?: number;
  status?: string;
}

type SlotApiPayload = RawSlotResponse[] | Response<RawSlotResponse[]>;

export interface CreateSlotPayload {
  labId: number;
  startTime: string;
  endTime: string;
  capacity: number;
  status?: string;
}

export interface CancelSlotPayload {
  slotId: number;
  reason?: string;
}

function unwrapSlots(payload: SlotApiPayload): RawSlotResponse[] {
  if (Array.isArray(payload)) {
    return payload;
  }

  return payload.data ?? [];
}

export async function getLabSlots(labId: number): Promise<RawSlotResponse[]> {
  const response = await apiClient.get<SlotApiPayload>(`/api/labs/${labId}/slots`);
  return unwrapSlots(response.data);
}

export async function getLabSlotHistory(labId: number): Promise<RawSlotResponse[]> {
  const response = await apiClient.get<SlotApiPayload>(`/api/labs/${labId}/slots/history`);
  return unwrapSlots(response.data);
}

export async function createSlot(payload: CreateSlotPayload): Promise<RawSlotResponse> {
  const response = await apiClient.post<Response<RawSlotResponse>>('/api/slots', payload);
  return response.data.data;
}

export async function getSlot(slotId: number): Promise<RawSlotResponse> {
  const response = await apiClient.get<Response<RawSlotResponse>>(`/api/slots/${slotId}`);
  return response.data.data;
}

export async function cancelSlot(payload: CancelSlotPayload): Promise<RawSlotResponse> {
  const response = await apiClient.patch<Response<RawSlotResponse>>(
    `/api/slots/${payload.slotId}/cancel`,
    {
      reason: payload.reason,
    },
  );
  return response.data.data;
}

export async function completeSlot(slotId: number): Promise<RawSlotResponse> {
  const response = await apiClient.patch<Response<RawSlotResponse>>(`/api/slots/${slotId}/complete`);
  return response.data.data;
}
