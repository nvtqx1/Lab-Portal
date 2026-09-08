import axios from 'axios';

import { apiClient } from '../../../shared/api';
import type { Response } from '../../../shared/types';
import type { FaceChallenge, FaceCheckinCandidate, FaceCheckinResult, FaceConsent, FaceConsentStatus, FaceGuidanceResult, FaceImageRequest, FaceProfile } from '../types';

function targetPath(userId: number | null, suffix: string) {
  return userId ? `/api/face/users/${userId}/${suffix}` : `/api/face/${suffix}`;
}

export async function getFaceConsent(userId: number | null): Promise<FaceConsent | null> {
  try {
    const response = await apiClient.get<Response<FaceConsent>>(targetPath(userId, 'consent'));
    return response.data.data;
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 404) return null;
    throw error;
  }
}

export async function changeFaceConsent(userId: number | null, status: FaceConsentStatus, reason?: string) {
  const response = await apiClient.post<Response<FaceConsent>>(targetPath(userId, 'consent'), {
    status,
    reason: reason?.trim() || undefined,
  });
  return response.data.data;
}

export async function getFaceProfile(userId: number | null): Promise<FaceProfile | null> {
  try {
    const response = await apiClient.get<Response<FaceProfile>>(targetPath(userId, 'profile'));
    return response.data.data;
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 404) return null;
    throw error;
  }
}

export async function getFaceProfiles(): Promise<FaceProfile[]> {
  const response = await apiClient.get<Response<FaceProfile[]>>('/api/face/profiles');
  return response.data.data;
}

export async function saveFaceProfile(userId: number | null, request: FaceImageRequest, update: boolean) {
  const path = userId
    ? `/api/face/users/${userId}/${update ? 'profile' : 'register'}`
    : `/api/face/${update ? 'profile' : 'register'}`;
  const response = update
    ? await apiClient.put<Response<FaceProfile>>(path, request)
    : await apiClient.post<Response<FaceProfile>>(path, request);
  return response.data.data;
}

export async function deleteFaceProfile(userId: number | null) {
  await apiClient.delete(targetPath(userId, 'profile'));
}

export async function getFaceGuidance(
  userId: number | null,
  request: Pick<FaceImageRequest, 'imageBase64' | 'contentType'>,
) {
  const response = await apiClient.post<Response<FaceGuidanceResult>>(targetPath(userId, 'guidance'), request);
  return response.data.data;
}

export async function startFaceChallenge(userId: number | null): Promise<FaceChallenge> {
  const response = await apiClient.post<Response<FaceChallenge>>(targetPath(userId, 'challenge'));
  return response.data.data;
}

export async function faceCheckin(slotId: number, request: Omit<FaceImageRequest, 'livenessRequired'>) {
  const response = await apiClient.post<Response<FaceCheckinResult>>('/api/face/check-in', {
    slotId,
    ...request,
  });
  return response.data.data;
}

export async function getFaceCheckinCandidates(): Promise<FaceCheckinCandidate[]> {
  const response = await apiClient.get<Response<FaceCheckinCandidate[]>>('/api/face/check-in/candidates');
  return response.data.data;
}

export async function getFaceCheckinGuidance(
  request: Pick<FaceImageRequest, 'imageBase64' | 'contentType'>,
) {
  const response = await apiClient.post<Response<FaceGuidanceResult>>('/api/face/check-in/guidance', request);
  return response.data.data;
}

export async function startFaceCheckinPassiveSession(): Promise<FaceChallenge> {
  const response = await apiClient.post<Response<FaceChallenge>>('/api/face/check-in/passive-session');
  return response.data.data;
}

export function readFaceImage(file: File): Promise<Pick<FaceImageRequest, 'imageBase64' | 'contentType'>> {
  if (file.type !== 'image/jpeg' && file.type !== 'image/png') {
    return Promise.reject(new Error('Chỉ hỗ trợ ảnh JPEG hoặc PNG.'));
  }
  if (file.size > 10 * 1024 * 1024) {
    return Promise.reject(new Error('Ảnh khuôn mặt không được vượt quá 10 MB.'));
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('Không thể đọc tệp ảnh.'));
    reader.onload = () => {
      const result = String(reader.result ?? '');
      const encoded = result.split(',', 2)[1];
      if (!encoded) {
        reject(new Error('Dữ liệu ảnh không hợp lệ.'));
        return;
      }
      resolve({ imageBase64: encoded, contentType: file.type as 'image/jpeg' | 'image/png' });
    };
    reader.readAsDataURL(file);
  });
}
