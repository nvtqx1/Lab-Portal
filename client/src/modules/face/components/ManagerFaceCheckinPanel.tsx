import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { Camera, CircleCheck, ScanFace } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';

import { queryKeys } from '../../../shared/api';
import { Button, EmptyState, ErrorState, Modal, toast } from '../../../shared/components';
import type { Response } from '../../../shared/types';
import { cameraErrorMessage, openCamera } from '../../../shared/utils';
import { invalidateAttendanceQueries } from '../../booking/hooks';
import {
  faceCheckin,
  getFaceCheckinCandidates,
  getFaceCheckinGuidance,
  startFaceCheckinPassiveSession,
} from '../api';
import type { FaceChallenge, FaceCheckinResult, FaceGuidanceResult, FaceImageRequest } from '../types';

type CameraFrame = Pick<FaceImageRequest, 'imageBase64' | 'contentType'>;
type FacePosition = Pick<FaceGuidanceResult, 'centerX' | 'centerY' | 'faceWidthRatio' | 'faceHeightRatio'>;

function wait(milliseconds: number) {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function errorMessage(error: unknown) {
  if (axios.isAxiosError(error)) {
    const body = error.response?.data as Partial<Response<unknown>> | undefined;
    return body?.message ?? body?.errors?.[0] ?? 'Không thể xác nhận khuôn mặt. Vui lòng thử lại.';
  }
  return error instanceof Error ? error.message : 'Không thể xác nhận khuôn mặt. Vui lòng thử lại.';
}

function faceFailureMessage(reason: string | null) {
  const messages: Record<string, string> = {
    NO_FACE: 'Không phát hiện khuôn mặt trong khung hình.',
    MULTIPLE_FACES: 'Có nhiều hơn một khuôn mặt trong khung hình.',
    LOW_QUALITY: 'Hình ảnh chưa đủ rõ hoặc ánh sáng chưa phù hợp.',
    NO_MATCH: 'Khuôn mặt không khớp với người đăng ký nào trong ca đã chọn.',
    SPOOF_DETECTED: 'Không xác minh được khuôn mặt thật.',
    CHALLENGE_MISSING: 'Thiếu dữ liệu quan sát trực tiếp từ camera. Hãy mở camera và thực hiện lại.',
    CHALLENGE_INVALID: 'Phiên xác minh đã hết hạn hoặc không hợp lệ. Hãy thực hiện lại.',
    CHALLENGE_FACE_INVALID: 'Mất khuôn mặt hoặc có nhiều người khi quay đầu. Hãy thực hiện lại.',
    CHALLENGE_START_NOT_FRONTAL: 'Ảnh đầu tiên chưa nhìn thẳng vào camera.',
    CHALLENGE_TURN_NOT_DETECTED: 'Chưa phát hiện đúng chuyển động quay đầu theo hướng dẫn.',
    OBSERVATION_TOO_SHORT: 'Camera chưa quan sát đủ lâu. Hãy nhìn thẳng và giữ yên thêm vài giây.',
    OBSERVATION_FACE_INVALID: 'Camera bị mất khuôn mặt, ảnh bị mờ hoặc có nhiều người trong lúc nhận diện.',
    OBSERVATION_NOT_FRONTAL: 'Hãy nhìn thẳng vào camera trong suốt quá trình nhận diện.',
    OBSERVATION_NOT_LIVE: 'Chuỗi camera chưa đủ biến thiên tự nhiên để xác minh khuôn mặt thật.',
    SERVICE_ERROR: 'Dịch vụ nhận diện khuôn mặt đang không khả dụng.',
  };
  return reason ? messages[reason] ?? reason : 'Không thể xác nhận khuôn mặt.';
}

function captureFrame(video: HTMLVideoElement, maxWidth?: number, quality = 0.84): Promise<CameraFrame> {
  const scale = maxWidth && video.videoWidth > maxWidth ? maxWidth / video.videoWidth : 1;
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(video.videoWidth * scale);
  canvas.height = Math.round(video.videoHeight * scale);
  const context = canvas.getContext('2d');
  if (!context) return Promise.reject(new Error('Không thể đọc hình ảnh từ camera.'));
  context.drawImage(video, 0, 0, canvas.width, canvas.height);
  const dataUrl = canvas.toDataURL('image/jpeg', quality);
  return Promise.resolve({ imageBase64: dataUrl.split(',', 2)[1], contentType: 'image/jpeg' });
}

function stablePosition(previous: FacePosition | null, current: FaceGuidanceResult) {
  if (!previous || previous.centerX === null || previous.centerY === null
    || previous.faceWidthRatio === null || previous.faceHeightRatio === null
    || current.centerX === null || current.centerY === null
    || current.faceWidthRatio === null || current.faceHeightRatio === null) return false;
  return Math.abs(previous.centerX - current.centerX) <= 0.025
    && Math.abs(previous.centerY - current.centerY) <= 0.025
    && Math.abs(previous.faceWidthRatio - current.faceWidthRatio) <= 0.035
    && Math.abs(previous.faceHeightRatio - current.faceHeightRatio) <= 0.035;
}

export function ManagerFaceCheckinPanel({ onCompleted }: { onCompleted: (result: FaceCheckinResult) => void }) {
  const queryClient = useQueryClient();
  const candidates = useQuery({
    queryKey: queryKeys.face.checkinCandidates,
    queryFn: getFaceCheckinCandidates,
  });
  const [slotId, setSlotId] = useState('');
  const [cameraDialogOpen, setCameraDialogOpen] = useState(false);
  const [completedBookingId, setCompletedBookingId] = useState<number | null>(null);
  const [recognizedStudentName, setRecognizedStudentName] = useState<string | null>(null);
  const [cameraActive, setCameraActive] = useState(false);
  const [cameraReady, setCameraReady] = useState(false);
  const [challenge, setChallenge] = useState<FaceChallenge | null>(null);
  const [instruction, setInstruction] = useState('Đưa khuôn mặt vào giữa khung oval');
  const [localError, setLocalError] = useState('');
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const previousPositionRef = useRef<FacePosition | null>(null);
  const stablePassesRef = useRef(0);
  const capturingRef = useRef(false);
  const slots = useMemo(() => Array.from(new Map(
    (candidates.data ?? []).map((candidate) => [candidate.slotId, {
      id: candidate.slotId,
      labName: candidate.labName,
      startTime: candidate.startTime,
      endTime: candidate.endTime,
    }]),
  ).values()), [candidates.data]);

  const stopCamera = () => {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
    setCameraActive(false);
    setCameraReady(false);
  };

  useEffect(() => () => stopCamera(), []);
  useEffect(() => {
    if (cameraActive && videoRef.current && streamRef.current) {
      videoRef.current.srcObject = streamRef.current;
    }
  }, [cameraActive]);

  const checkin = useMutation({
    mutationFn: ({ primary, frames, activeChallenge }: {
      primary: CameraFrame;
      frames: CameraFrame[];
      activeChallenge: FaceChallenge;
    }) => faceCheckin(Number(slotId), {
      ...primary,
      challengeFrames: frames,
      challengeToken: activeChallenge.challengeToken,
    }),
    onSuccess: (result) => {
      if (!result.checkedIn) {
        setLocalError(faceFailureMessage(result.failureReason ?? result.result));
        return;
      }
      if (result.bookingId === null) {
        setLocalError('Không nhận được lượt đặt của người đã nhận diện.');
        return;
      }
      toast.success('Đã nhận diện đúng thành viên và check-in thành công.');
      const completedCandidate = candidates.data?.find((candidate) => candidate.bookingId === result.bookingId);
      if (completedCandidate) invalidateAttendanceQueries(queryClient, completedCandidate);
      stopCamera();
      setCompletedBookingId(result.bookingId);
      setRecognizedStudentName(result.studentName);
      onCompleted(result);
    },
    onError: (error) => setLocalError(errorMessage(error)),
  });

  const runCapture = async () => {
    const video = videoRef.current;
    const activeChallenge = challenge;
    if (!video || !activeChallenge || capturingRef.current) return;
    capturingRef.current = true;
    try {
      setInstruction('Đang nhận diện tự động — nhìn thẳng và giữ vị trí tự nhiên');
      const primary = await captureFrame(video, undefined, 0.92);
      const frames: CameraFrame[] = [];
      for (let index = 0; index < 6; index += 1) {
        setInstruction(`Đang quan sát khuôn mặt trực tiếp — không cần quay đầu (${index + 1}/6)`);
        await wait(400);
        frames.push(await captureFrame(video, undefined, 0.92));
      }
      setInstruction('Đang đối chiếu với những người đã đăng ký trong ca');
      await checkin.mutateAsync({ primary, frames, activeChallenge });
    } catch (error) {
      setLocalError(errorMessage(error));
    } finally {
      capturingRef.current = false;
    }
  };

  useEffect(() => {
    if (!cameraActive || !cameraReady) return undefined;
    let cancelled = false;
    let timer: number | undefined;
    const evaluate = async () => {
      const video = videoRef.current;
      if (!video || capturingRef.current || video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) {
        timer = window.setTimeout(evaluate, 800);
        return;
      }
      try {
        const result = await getFaceCheckinGuidance(await captureFrame(video, 640, 0.76));
        if (cancelled) return;
        const stable = stablePosition(previousPositionRef.current, result);
        const passed = result.singleFace && result.faceInGuide && result.facingForward
          && result.landmarksVisible && result.lightingGood && result.sharpnessGood && stable;
        const nextInstruction = !result.singleFace
          ? result.detectedFaces === 0 ? 'Đưa một khuôn mặt vào giữa khung' : 'Chỉ để một người trong khung hình'
          : !result.faceInGuide
            ? 'Di chuyển khuôn mặt vào giữa oval và điều chỉnh khoảng cách'
            : !result.facingForward || !result.landmarksVisible
              ? 'Nhìn thẳng và không che mắt, mũi hoặc miệng'
              : !result.lightingGood
                ? 'Điều chỉnh để ánh sáng chiếu từ phía trước'
                : !result.sharpnessGood || !stable
                  ? 'Giữ yên khuôn mặt để ảnh rõ nét'
                  : 'Giữ nguyên vị trí — hệ thống đang chuẩn bị nhận diện';
        previousPositionRef.current = result.singleFace ? result : null;
        stablePassesRef.current = passed ? stablePassesRef.current + 1 : 0;
        setInstruction(nextInstruction);
        if (stablePassesRef.current >= 3 && !capturingRef.current) void runCapture();
      } catch (error) {
        if (!cancelled) setLocalError(errorMessage(error));
      } finally {
        if (!cancelled) timer = window.setTimeout(evaluate, 800);
      }
    };
    void evaluate();
    return () => {
      cancelled = true;
      if (timer) window.clearTimeout(timer);
    };
  }, [cameraActive, cameraReady, challenge, slotId]);

  const startCamera = async () => {
    if (!slotId) {
      setLocalError('Hãy chọn ca sử dụng trước khi mở camera.');
      return;
    }
    setCameraDialogOpen(true);
    setCompletedBookingId(null);
    setRecognizedStudentName(null);
    try {
      const stream = await openCamera('user');
      streamRef.current = stream;
      const activeChallenge = await startFaceCheckinPassiveSession();
      if (activeChallenge.action !== 'OBSERVE') throw new Error('Phiên quan sát camera không hợp lệ.');
      setChallenge(activeChallenge);
      setLocalError('');
      setInstruction('Đưa khuôn mặt vào giữa khung oval');
      previousPositionRef.current = null;
      stablePassesRef.current = 0;
      setCameraActive(true);
    } catch (error) {
      stopCamera();
      setLocalError(cameraErrorMessage(error));
    }
  };

  const closeCameraDialog = () => {
    if (checkin.isPending || capturingRef.current) return;
    stopCamera();
    setCameraDialogOpen(false);
    setCompletedBookingId(null);
    setRecognizedStudentName(null);
    setSlotId('');
    setLocalError('');
  };

  if (candidates.isLoading) return <div className="mt-4 h-48 animate-pulse rounded bg-slate-100 dark:bg-slate-800" />;
  if (candidates.isError) return <ErrorState className="mt-4" onRetry={() => void candidates.refetch()}>Không thể tải các ca đang chờ check-in.</ErrorState>;
  if ((candidates.data?.length ?? 0) === 0) return <EmptyState className="mt-4">Không có lượt đặt đã duyệt đang chờ check-in trong PTN bạn quản lý.</EmptyState>;

  return (
    <div className="mt-4">
      <div className="max-w-3xl">
        <label className="block text-sm font-semibold text-slate-700 dark:text-slate-200" htmlFor="face-slot">
          Ca sử dụng
          <select id="face-slot" className="mt-2 min-h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-sm text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-60 dark:border-slate-700 dark:bg-slate-900 dark:text-white" disabled={cameraActive} value={slotId} onChange={(event) => setSlotId(event.target.value)}>
            <option value="">Chọn ca đang trong thời gian check-in</option>
            {slots.map((slot) => <option key={slot.id} value={slot.id}>{slot.labName} — {new Date(slot.startTime).toLocaleString('vi-VN')}–{new Date(slot.endTime).toLocaleTimeString('vi-VN')}</option>)}
          </select>
        </label>
        <div className="mt-3 flex flex-wrap gap-2">
          <Button disabled={!slotId || cameraActive} type="button" onClick={() => void startCamera()}><Camera aria-hidden="true" className="h-4 w-4" /> Mở camera và tự động nhận diện</Button>
        </div>
        {localError && !cameraDialogOpen ? <p className="mt-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm font-medium text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-200" role="alert">{localError}</p> : null}
      </div>

      <Modal
        backdropBlur
        centered
        closeDisabled={checkin.isPending || capturingRef.current}
        closeOnEscape
        isOpen={cameraDialogOpen}
        onClose={closeCameraDialog}
        size={completedBookingId ? 'lg' : '2xl'}
        subtitle={completedBookingId ? 'Kết quả nhận diện đã được ghi nhận.' : 'Căn khuôn mặt vào giữa khung oval và nhìn thẳng vào camera.'}
        title={completedBookingId ? 'Kết quả nhận diện khuôn mặt' : 'Camera nhận diện khuôn mặt'}
      >
        {completedBookingId ? (
          <div className="flex flex-col items-center py-8 text-center" role="status">
            <span className="flex h-16 w-16 items-center justify-center rounded-full bg-emerald-100 text-emerald-700"><CircleCheck aria-hidden="true" className="h-9 w-9" /></span>
            <h3 className="mt-4 text-xl font-semibold text-slate-950">Check-in thành công</h3>
            {recognizedStudentName ? <p className="mt-2 text-base font-semibold text-slate-800">{recognizedStudentName}</p> : null}
            <p className="mt-2 text-sm text-slate-600">Lượt đặt #{completedBookingId} đã chuyển sang trạng thái “Đã xác nhận có mặt”.</p>
            <Button className="mt-6" type="button" onClick={closeCameraDialog}>Hoàn tất</Button>
          </div>
        ) : (
          <div className="mx-auto w-full max-w-4xl">
              <div className="relative aspect-video overflow-hidden rounded-md bg-slate-950 shadow-lg">
                {cameraActive ? <video ref={videoRef} autoPlay muted playsInline className="h-full w-full object-cover" aria-label="Camera nhận diện khuôn mặt tại bàn check-in" onLoadedMetadata={() => setCameraReady(true)} /> : <div className="flex h-full items-center justify-center text-slate-400"><ScanFace aria-hidden="true" className="h-16 w-16" /></div>}
                {cameraActive ? <div aria-hidden="true" className="pointer-events-none absolute inset-x-1/4 inset-y-4 rounded-full border-2 border-dashed border-white/90 shadow-[0_0_0_999px_rgba(15,23,42,0.28)]" /> : null}
                {cameraActive ? <p aria-live="assertive" className="absolute inset-x-3 bottom-3 rounded bg-slate-950/80 px-3 py-2 text-center text-sm font-semibold text-white">{instruction}</p> : null}
                {localError ? <p className="absolute inset-x-3 top-3 rounded bg-red-700/90 px-3 py-2 text-center text-sm font-medium text-white" role="alert">{localError}</p> : null}
              </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
