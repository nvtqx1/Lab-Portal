import axios from 'axios';
import { Camera, CircleCheck, Shield, Trash2 } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import { getStoredRole } from '../../../shared/api';
import { Button, ConfirmDialog, EmptyState, ErrorState, Modal } from '../../../shared/components';
import type { Response } from '../../../shared/types';
import { cameraErrorMessage, openCamera } from '../../../shared/utils';
import { useProfile } from '../../user/hooks';
import { getFaceGuidance, readFaceImage, startFaceChallenge } from '../api';
import { useFaceProfile, useFaceProfileActions, useFaceProfiles } from '../hooks';
import type { FaceChallenge, FaceGuidanceResult, FaceImageRequest } from '../types';

function errorMessage(error: unknown) {
  if (axios.isAxiosError(error)) {
    const body = error.response?.data as Partial<Response<unknown>> | undefined;
    if (body?.message === 'Face registration did not produce an acceptable embedding') {
      return 'Ảnh khuôn mặt chưa đạt yêu cầu. Hãy chụp chính diện, đủ sáng, rõ nét và không che khuôn mặt.';
    }
    if (body?.message === 'Face service is temporarily unavailable') {
      return 'Dịch vụ nhận diện khuôn mặt đang tạm thời không khả dụng. Vui lòng thử lại sau.';
    }
    return body?.message === 'Camera API is unavailable'
      ? 'Trình duyệt không hỗ trợ camera.'
      : 'Không thể cập nhật hồ sơ khuôn mặt.';
  }
  return error instanceof Error ? error.message : 'Không thể cập nhật hồ sơ khuôn mặt.';
}

type FacePosition = Pick<FaceGuidanceResult, 'centerX' | 'centerY' | 'faceWidthRatio' | 'faceHeightRatio'>;
type CapturePhase = 'IDLE' | 'FRONT' | 'LEFT_PREPARE' | 'LEFT_CAPTURE' | 'RIGHT_PREPARE' | 'RIGHT_CAPTURE' | 'COMPLETE';

function wait(milliseconds: number) {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function readCameraFrame(video: HTMLVideoElement, maxWidth?: number, quality = 0.82) {
  const scale = maxWidth && video.videoWidth > maxWidth ? maxWidth / video.videoWidth : 1;
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(video.videoWidth * scale);
  canvas.height = Math.round(video.videoHeight * scale);
  const context = canvas.getContext('2d');
  if (!context) return Promise.reject(new Error('Không thể đọc hình ảnh từ camera.'));
  context.drawImage(video, 0, 0, canvas.width, canvas.height);
  return new Promise<Pick<FaceImageRequest, 'imageBase64' | 'contentType'>>((resolve, reject) => {
    canvas.toBlob(async (blob) => {
      if (!blob) {
        reject(new Error('Không thể tạo ảnh từ camera.'));
        return;
      }
      try {
        resolve(await readFaceImage(new File([blob], 'camera-face.jpg', { type: 'image/jpeg' })));
      } catch (error) {
        reject(error);
      }
    }, 'image/jpeg', quality);
  });
}

async function readChallengeFrames(video: HTMLVideoElement, onProgress: (captured: number) => void) {
  const frames: Array<Pick<FaceImageRequest, 'imageBase64' | 'contentType'>> = [];
  for (let index = 0; index < 6; index += 1) {
    await wait(450);
    frames.push(await readCameraFrame(video, undefined, 0.92));
    onProgress(index + 1);
  }
  return frames;
}

function isStablePosition(previous: FacePosition | null, current: FaceGuidanceResult) {
  if (!previous || previous.centerX === null || previous.centerY === null
      || previous.faceWidthRatio === null || previous.faceHeightRatio === null
      || current.centerX === null || current.centerY === null
      || current.faceWidthRatio === null || current.faceHeightRatio === null) return false;
  return Math.abs(previous.centerX - current.centerX) <= 0.025
    && Math.abs(previous.centerY - current.centerY) <= 0.025
    && Math.abs(previous.faceWidthRatio - current.faceWidthRatio) <= 0.035
    && Math.abs(previous.faceHeightRatio - current.faceHeightRatio) <= 0.035;
}

export function FaceProfilePage() {
  const isAdmin = getStoredRole()?.replace(/^ROLE_/, '') === 'ADMIN';
  const currentUser = useProfile();
  const hasActiveLabMembership = currentUser.data?.memberships?.some(
    (membership) => membership.status?.toUpperCase() === 'ACTIVE',
  ) ?? false;
  const [targetInput, setTargetInput] = useState('');
  const [pendingConfirmation, setPendingConfirmation] = useState<'WITHDRAW_CONSENT' | 'REMOVE_PROFILE' | null>(null);
  const targetUserId = isAdmin && Number(targetInput) > 0 ? Number(targetInput) : null;
  const enabled = isAdmin ? targetUserId !== null : hasActiveLabMembership;
  const adminProfiles = useFaceProfiles(isAdmin);
  const { consent, profile } = useFaceProfile(targetUserId, enabled);
  const actions = useFaceProfileActions(targetUserId);
  const [image, setImage] = useState<(Awaited<ReturnType<typeof readFaceImage>>
    & Pick<FaceImageRequest, 'challengeToken' | 'challengeFrames' | 'sideImages'>) | null>(null);
  const [previewUrl, setPreviewUrl] = useState('');
  const [privacyAccepted, setPrivacyAccepted] = useState(false);
  const [localError, setLocalError] = useState('');
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const [cameraActive, setCameraActive] = useState(false);
  const [cameraDialogOpen, setCameraDialogOpen] = useState(false);
  const [faceSaveComplete, setFaceSaveComplete] = useState(false);
  const [cameraReady, setCameraReady] = useState(false);
  const [guidance, setGuidance] = useState<FaceGuidanceResult | null>(null);
  const [stable, setStable] = useState(false);
  const [challenge, setChallenge] = useState<FaceChallenge | null>(null);
  const [countdown, setCountdown] = useState<number | null>(null);
  const [capturePhase, setCapturePhase] = useState<CapturePhase>('IDLE');
  const [turnCountdown, setTurnCountdown] = useState<number | null>(null);
  const [capturedChallengeFrames, setCapturedChallengeFrames] = useState(0);
  const previousPositionRef = useRef<FacePosition | null>(null);
  const stablePassesRef = useRef(0);
  const guidanceReadyRef = useRef(false);
  const captureCameraRef = useRef<() => void>(() => undefined);
  const capturingRef = useRef(false);

  useEffect(() => {
    if (cameraActive && videoRef.current && streamRef.current) {
      videoRef.current.srcObject = streamRef.current;
    }
  }, [cameraActive]);

  useEffect(() => () => streamRef.current?.getTracks().forEach((track) => track.stop()), []);

  useEffect(() => {
    if (!cameraActive || !cameraReady) return undefined;
    let cancelled = false;
    let timer: number | undefined;
    const evaluate = async () => {
      const video = videoRef.current;
      if (!video || video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA || capturingRef.current) {
        timer = window.setTimeout(evaluate, 800);
        return;
      }
      try {
        const result = await getFaceGuidance(targetUserId, await readCameraFrame(video, 640, 0.76));
        if (cancelled) return;
        const positionStable = isStablePosition(previousPositionRef.current, result);
        const serverChecksPassed = result.singleFace && result.faceInGuide && result.facingForward
          && result.landmarksVisible && result.lightingGood && result.sharpnessGood;
        previousPositionRef.current = result.singleFace ? result : null;
        guidanceReadyRef.current = serverChecksPassed && positionStable;
        stablePassesRef.current = guidanceReadyRef.current ? stablePassesRef.current + 1 : 0;
        setGuidance(result);
        setStable(positionStable);
        setLocalError('');
        if (stablePassesRef.current >= 3) setCountdown((current) => current ?? 3);
        if (!guidanceReadyRef.current) setCountdown(null);
      } catch (error) {
        if (cancelled) return;
        guidanceReadyRef.current = false;
        stablePassesRef.current = 0;
        previousPositionRef.current = null;
        setGuidance(null);
        setStable(false);
        setCountdown(null);
        setLocalError(errorMessage(error));
      } finally {
        if (!cancelled) {
          timer = window.setTimeout(evaluate, 800);
        }
      }
    };
    void evaluate();
    return () => {
      cancelled = true;
      if (timer) window.clearTimeout(timer);
    };
  }, [cameraActive, cameraReady, targetUserId]);

  useEffect(() => {
    if (countdown === null || !cameraActive) return undefined;
    if (!guidanceReadyRef.current) {
      setCountdown(null);
      return undefined;
    }
    if (countdown === 0) {
      setCountdown(null);
      captureCameraRef.current();
      return undefined;
    }
    const timer = window.setTimeout(() => setCountdown((current) => current === null ? null : current - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [cameraActive, countdown]);

  useEffect(() => () => {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
  }, [previewUrl]);

  const startCamera = async () => {
    setCameraDialogOpen(true);
    setFaceSaveComplete(false);
    setImage(null);
    setPreviewUrl('');
    try {
      streamRef.current = await openCamera('user');
      const nextChallenge = await startFaceChallenge(targetUserId);
      setChallenge(nextChallenge);
      actions.save.reset();
      setCameraReady(false);
      setGuidance(null);
      setStable(false);
      setCountdown(null);
      setCapturePhase('IDLE');
      setTurnCountdown(null);
      setCapturedChallengeFrames(0);
      previousPositionRef.current = null;
      stablePassesRef.current = 0;
      guidanceReadyRef.current = false;
      setCameraActive(true);
      setLocalError('');
    } catch (cameraError) {
      streamRef.current?.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
      setChallenge(null);
      setLocalError(cameraErrorMessage(cameraError));
    }
  };

  const requestConsentAndOpenCamera = () => {
    if (consent.data?.status === 'GRANTED') {
      void startCamera();
      return;
    }
    if (!privacyAccepted) {
      setLocalError('Bạn cần xác nhận đồng ý sử dụng dữ liệu khuôn mặt trước khi mở camera.');
      return;
    }
    setLocalError('');
    actions.consent.mutate(
      { status: 'GRANTED' },
      { onSuccess: () => void startCamera() },
    );
  };

  const withdrawConsent = () => {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    setCameraActive(false);
    setCameraDialogOpen(false);
    setCameraReady(false);
    setImage(null);
    setPreviewUrl('');
    setPrivacyAccepted(false);
    actions.consent.mutate(
      { status: 'WITHDRAWN' },
      { onSuccess: () => setPendingConfirmation(null) },
    );
  };

  const confirmFaceAction = () => {
    if (pendingConfirmation === 'WITHDRAW_CONSENT') {
      withdrawConsent();
    } else if (pendingConfirmation === 'REMOVE_PROFILE') {
      actions.remove.mutate(undefined, { onSuccess: () => setPendingConfirmation(null) });
    }
  };

  const captureCamera = () => {
    const video = videoRef.current;
    if (!video || !cameraReady || !guidanceReadyRef.current || video.videoWidth === 0 || capturingRef.current) {
      setLocalError('Các điều kiện nhận diện chưa đạt. Hãy làm theo cảnh báo hiển thị cạnh camera.');
      return;
    }
    capturingRef.current = true;
    setCapturePhase('FRONT');
    setCapturedChallengeFrames(0);
    void (async () => {
      try {
        const result = await readCameraFrame(video, undefined, 0.92);
        setCapturePhase('LEFT_PREPARE');
        for (let remaining = 3; remaining > 0; remaining -= 1) {
          setTurnCountdown(remaining);
          await wait(1000);
        }
        setTurnCountdown(null);
        setCapturePhase('LEFT_CAPTURE');
        const leftFrames = await readChallengeFrames(video, (captured) => setCapturedChallengeFrames(captured));
        setCapturePhase('RIGHT_PREPARE');
        for (let remaining = 3; remaining > 0; remaining -= 1) {
          setTurnCountdown(remaining);
          await wait(1000);
        }
        setTurnCountdown(null);
        setCapturePhase('RIGHT_CAPTURE');
        const rightFrames = await readChallengeFrames(video, (captured) => setCapturedChallengeFrames(6 + captured));
        const challengeFrames = [...leftFrames.slice(-3), ...rightFrames.slice(-3)];
        setCapturePhase('COMPLETE');
        await wait(800);
        setImage({
          ...result,
          challengeToken: challenge?.challengeToken,
          challengeFrames,
          sideImages: [leftFrames[leftFrames.length - 1], rightFrames[rightFrames.length - 1]],
        });
        setPreviewUrl(`data:${result.contentType};base64,${result.imageBase64}`);
        streamRef.current?.getTracks().forEach((track) => track.stop());
        setCameraActive(false);
        setCameraReady(false);
        setGuidance(null);
        setStable(false);
        setCountdown(null);
        setLocalError('');
      } catch (error) {
        setLocalError(errorMessage(error));
      } finally {
        capturingRef.current = false;
        setCapturePhase('IDLE');
        setTurnCountdown(null);
      }
    })();
  };

  captureCameraRef.current = captureCamera;

  const saveCapturedFace = () => {
    if (!image) {
      setLocalError('Vui lòng mở camera và chụp ảnh khuôn mặt rõ nét.');
      return;
    }
    setLocalError('');
    actions.save.mutate(
      { request: { ...image, livenessRequired: true }, update: Boolean(profile.data) },
      { onSuccess: () => setFaceSaveComplete(true) },
    );
  };

  const closeCameraDialog = () => {
    if (actions.save.isPending || capturingRef.current) return;
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    setCameraActive(false);
    setCameraReady(false);
    setCameraDialogOpen(false);
    setGuidance(null);
    setCountdown(null);
    setLocalError('');
  };

  const mutationError = actions.consent.error ?? actions.save.error ?? actions.remove.error;
  const allConditionsPassed = Boolean(guidance?.singleFace && guidance.faceInGuide
    && guidance.facingForward && guidance.landmarksVisible && guidance.lightingGood
    && guidance.sharpnessGood && stable);
  const adjustmentInstruction = !cameraReady
    ? 'Đang khởi tạo camera...'
    : !guidance?.singleFace
      ? guidance?.detectedFaces === 0 ? 'Đưa một khuôn mặt vào giữa khung' : 'Chỉ để một người trong khung hình'
      : !guidance.faceInGuide
        ? 'Di chuyển khuôn mặt vào giữa oval và điều chỉnh khoảng cách'
        : !guidance.facingForward || !guidance.landmarksVisible
          ? 'Nhìn thẳng và không che mắt, mũi hoặc miệng'
          : !guidance.lightingGood
            ? 'Điều chỉnh để ánh sáng chiếu từ phía trước'
            : !guidance.sharpnessGood || !stable
              ? 'Giữ yên khuôn mặt để ảnh rõ nét'
              : 'Giữ nguyên vị trí — hệ thống sẽ tự chụp';
  const captureInstruction = capturePhase === 'FRONT'
    ? 'ĐANG CHỤP ẢNH CHÍNH DIỆN — tiếp tục nhìn thẳng'
    : capturePhase === 'LEFT_PREPARE'
      ? `CHUẨN BỊ: quay đầu nhẹ sang TRÁI sau ${turnCountdown ?? 0} giây`
      : capturePhase === 'LEFT_CAPTURE'
        ? `ĐANG GHI GÓC TRÁI — giữ khuôn mặt trong khung (${capturedChallengeFrames}/12)`
        : capturePhase === 'RIGHT_PREPARE'
          ? `CHUẨN BỊ: quay đầu nhẹ sang PHẢI sau ${turnCountdown ?? 0} giây`
          : capturePhase === 'RIGHT_CAPTURE'
            ? `ĐANG GHI GÓC PHẢI — giữ khuôn mặt trong khung (${capturedChallengeFrames}/12)`
        : capturePhase === 'COMPLETE'
          ? 'ĐÃ GHI NHẬN CHÍNH DIỆN, TRÁI VÀ PHẢI — đang hoàn tất'
          : adjustmentInstruction;
  return (
    <section className="mx-auto max-w-5xl">
      <header className="mb-6">
        <p className="flex items-center gap-2 text-sm font-semibold uppercase tracking-wide text-slate-500"><Shield aria-hidden="true" className="h-4 w-4" /> Sinh trắc học có kiểm soát</p>
        <h1 className="mt-1 text-2xl font-semibold text-slate-950 dark:text-white">Hồ sơ khuôn mặt</h1>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-slate-600 dark:text-slate-300">Ảnh chỉ được dùng để tạo mẫu nhận diện đã mã hóa và phục vụ check-in.</p>
      </header>

      {isAdmin ? (
        <section className="mb-5 rounded-lg border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900">
          <div className="flex flex-wrap items-end justify-between gap-4">
            <div>
              <h2 className="text-base font-semibold text-slate-950 dark:text-white">Hồ sơ khuôn mặt đã đăng ký</h2>
              <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">Chỉ hiển thị metadata; embedding khuôn mặt không được trả về giao diện.</p>
            </div>
            <label className="block w-full max-w-xs text-sm font-semibold text-slate-700 dark:text-slate-200" htmlFor="face-target-user">
              Tra cứu trực tiếp theo User ID
              <input id="face-target-user" className="mt-2 min-h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-base dark:border-slate-700 dark:bg-slate-950 dark:text-white" min={1} type="number" value={targetInput} onChange={(event) => setTargetInput(event.target.value)} />
            </label>
          </div>
          {adminProfiles.isLoading ? <div className="mt-4 h-24 animate-pulse rounded bg-slate-100 dark:bg-slate-800" />
            : adminProfiles.isError ? <ErrorState className="mt-4" onRetry={() => void adminProfiles.refetch()}>Không thể tải danh sách hồ sơ khuôn mặt.</ErrorState>
              : (adminProfiles.data?.length ?? 0) === 0 ? <EmptyState className="mt-4">Chưa có hồ sơ khuôn mặt nào được đăng ký.</EmptyState>
                : <div className="mt-4 overflow-x-auto"><table className="w-full min-w-[640px] text-left text-sm"><thead className="border-b border-slate-200 text-xs uppercase text-slate-500 dark:border-slate-700 dark:text-slate-400"><tr><th className="px-3 py-2">User ID</th><th className="px-3 py-2">Trạng thái</th><th className="px-3 py-2">Model</th><th className="px-3 py-2">Cập nhật</th><th className="px-3 py-2 text-right">Thao tác</th></tr></thead><tbody className="divide-y divide-slate-200 dark:divide-slate-800">{adminProfiles.data?.map((item) => <tr className={targetUserId === item.userId ? 'bg-blue-50 dark:bg-blue-950/30' : ''} key={item.userId}><td className="px-3 py-3 font-semibold text-slate-950 dark:text-white">{item.userId}</td><td className="px-3 py-3 text-slate-600 dark:text-slate-300">{item.status}</td><td className="px-3 py-3 text-slate-600 dark:text-slate-300">{item.embeddingModel}</td><td className="px-3 py-3 text-slate-600 dark:text-slate-300">{new Date(item.updatedAt).toLocaleString('vi-VN')}</td><td className="px-3 py-3 text-right"><Button type="button" variant="outline" onClick={() => setTargetInput(String(item.userId))}>Quản lý</Button></td></tr>)}</tbody></table></div>}
        </section>
      ) : null}

      {!isAdmin && currentUser.isLoading ? <div className="h-32 animate-pulse rounded-lg bg-slate-100 dark:bg-slate-800" aria-label="Đang kiểm tra quyền đăng ký khuôn mặt" /> : !enabled ? <EmptyState>{isAdmin ? 'Nhập ID người dùng để xem trạng thái đồng ý và hồ sơ khuôn mặt.' : 'Bạn cần được duyệt vào ít nhất một phòng thí nghiệm trước khi đăng ký khuôn mặt.'}</EmptyState> : (
        <div>
          <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <h2 className="flex items-center gap-2 text-base font-semibold text-slate-950 dark:text-white"><Camera aria-hidden="true" className="h-5 w-5" /> Mẫu khuôn mặt</h2>
            {profile.isLoading ? <div className="mt-4 h-20 animate-pulse rounded bg-slate-100 dark:bg-slate-800" /> : profile.isError ? <ErrorState className="mt-4" onRetry={() => void profile.refetch()}>Không thể tải hồ sơ khuôn mặt.</ErrorState> : (
              <div className="mt-4">
                {profile.data ? <div className="mb-4 rounded-md bg-slate-50 p-3 text-sm text-slate-600 dark:bg-slate-950 dark:text-slate-300"><p>Trạng thái: <strong>{profile.data.status}</strong></p><p className="mt-1">Model: {profile.data.embeddingModel}</p></div> : <p className="mb-4 text-sm text-slate-600 dark:text-slate-300">Chưa đăng ký hồ sơ khuôn mặt.</p>}
                {consent.isLoading ? <div className="mb-4 h-28 animate-pulse rounded bg-slate-100 dark:bg-slate-800" aria-label="Đang tải thông tin quyền riêng tư" /> : consent.isError ? <ErrorState className="mb-4" onRetry={() => void consent.refetch()}>Không thể tải trạng thái đồng ý sử dụng dữ liệu.</ErrorState> : (
                  <div className={`mb-4 rounded-md border p-4 ${consent.data?.status === 'GRANTED' ? 'border-emerald-200 bg-emerald-50 dark:border-emerald-900 dark:bg-emerald-950/40' : 'border-blue-200 bg-blue-50 dark:border-blue-900 dark:bg-blue-950/40'}`}>
                    <div className="flex items-start gap-3">
                      <Shield aria-hidden="true" className="mt-0.5 h-5 w-5 shrink-0 text-blue-700 dark:text-blue-300" />
                      <div>
                        <h3 className="text-sm font-semibold text-slate-950 dark:text-white">Quyền sử dụng dữ liệu khuôn mặt và camera</h3>
                        {consent.data?.status === 'GRANTED' ? (
                          <p className="mt-1 text-sm text-emerald-800 dark:text-emerald-200">Bạn đã đồng ý cho hệ thống xử lý dữ liệu khuôn mặt phục vụ check-in phòng thí nghiệm.</p>
                        ) : (
                          <>
                            <p className="mt-1 text-sm leading-6 text-slate-700 dark:text-slate-200">Bạn có đồng ý cho Lab Portal sử dụng dữ liệu khuôn mặt của bạn để tạo mẫu nhận diện và phục vụ check-in phòng thí nghiệm không?</p>
                            <ul className="mt-2 list-disc space-y-1 pl-5 text-xs leading-5 text-slate-600 dark:text-slate-300">
                              <li>Camera chỉ được mở sau khi bạn xác nhận và trình duyệt cấp quyền.</li>
                              <li>Ảnh camera được xử lý tạm thời; hệ thống chỉ lưu embedding đã mã hóa.</li>
                              <li>Bạn có thể rút lại đồng ý và xóa hồ sơ khuôn mặt bất kỳ lúc nào.</li>
                            </ul>
                            <label className="mt-3 flex cursor-pointer items-start gap-2 text-sm font-medium text-slate-800 dark:text-slate-100">
                              <input className="mt-0.5 h-4 w-4 rounded border-slate-300" type="checkbox" checked={privacyAccepted} onChange={(event) => { setPrivacyAccepted(event.target.checked); setLocalError(''); }} />
                              <span>Tôi đã đọc, hiểu và đồng ý cho hệ thống xử lý dữ liệu khuôn mặt của tôi.</span>
                            </label>
                          </>
                        )}
                      </div>
                    </div>
                  </div>
                )}
                <Button type="button" variant={consent.data?.status === 'GRANTED' ? 'outline' : 'success'} disabled={consent.isLoading || consent.isError || (consent.data?.status !== 'GRANTED' && !privacyAccepted)} loading={actions.consent.isPending} loadingText="Đang ghi nhận đồng ý..." onClick={requestConsentAndOpenCamera}><Camera aria-hidden="true" className="h-4 w-4" /> {consent.data?.status === 'GRANTED' ? 'Mở camera' : 'Tôi đồng ý và mở camera'}</Button>
                <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">Ảnh được chụp trực tiếp để kiểm tra chất lượng và liveness; hệ thống chỉ lưu embedding đã mã hóa.</p>
                <div className="mt-4 flex flex-wrap gap-2">
                  {profile.data ? <Button aria-label="Xóa hồ sơ khuôn mặt" loading={actions.remove.isPending} variant="danger" onClick={() => setPendingConfirmation('REMOVE_PROFILE')}><Trash2 aria-hidden="true" className="h-4 w-4" /> Xóa hồ sơ</Button> : null}
                  {consent.data?.status === 'GRANTED' ? <Button type="button" loading={actions.consent.isPending} variant="outline" onClick={() => setPendingConfirmation('WITHDRAW_CONSENT')}>Rút lại đồng ý</Button> : null}
                </div>
              </div>
            )}
          </section>
        </div>
      )}

      <Modal
        backdropBlur
        centered
        closeDisabled={actions.save.isPending || capturePhase !== 'IDLE'}
        closeOnEscape
        isOpen={cameraDialogOpen}
        onClose={closeCameraDialog}
        size={faceSaveComplete ? 'lg' : '2xl'}
        subtitle={faceSaveComplete ? 'Kết quả đã được lưu an toàn vào hồ sơ.' : image && !cameraActive ? 'Kiểm tra kết quả trước khi lưu hồ sơ khuôn mặt.' : 'Giữ khuôn mặt giữa khung và làm theo hướng dẫn trên màn hình.'}
        title={faceSaveComplete ? 'Kết quả đăng ký khuôn mặt' : image && !cameraActive ? 'Kết quả chụp khuôn mặt' : 'Camera đăng ký khuôn mặt'}
      >
        {faceSaveComplete ? (
          <div className="flex flex-col items-center py-8 text-center" role="status">
            <span className="flex h-16 w-16 items-center justify-center rounded-full bg-emerald-100 text-emerald-700"><CircleCheck aria-hidden="true" className="h-9 w-9" /></span>
            <h3 className="mt-4 text-xl font-semibold text-slate-950">Đã lưu hồ sơ khuôn mặt</h3>
            <p className="mt-2 max-w-lg text-sm text-slate-600">Mẫu chính diện và các góc kiểm tra đã được xử lý. Hệ thống chỉ lưu embedding đã mã hóa.</p>
            <Button className="mt-6" type="button" onClick={closeCameraDialog}>Hoàn tất</Button>
          </div>
        ) : image && !cameraActive ? (
          <div className="mx-auto max-w-2xl">
            <img alt="Ảnh khuôn mặt chính diện vừa chụp" className="aspect-video w-full rounded-md border border-slate-200 object-cover" src={previewUrl} />
            <div className="mt-4 flex items-start gap-2 rounded-md bg-emerald-50 p-3 text-sm text-emerald-800" role="status"><CircleCheck aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0" /><span>Đã thu đủ mẫu chính diện, trái và phải. Bạn có thể lưu kết quả hoặc chụp lại.</span></div>
            {actions.save.error ? <p className="mt-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700" role="alert">{errorMessage(actions.save.error)}</p> : null}
            <div className="mt-5 flex flex-col-reverse justify-end gap-2 sm:flex-row">
              <Button disabled={actions.save.isPending} type="button" variant="outline" onClick={() => void startCamera()}>Chụp lại</Button>
              <Button loading={actions.save.isPending} loadingText="Đang xử lý..." type="button" onClick={saveCapturedFace}>{profile.data ? 'Cập nhật mẫu này' : 'Đăng ký mẫu này'}</Button>
            </div>
          </div>
        ) : (
          <div className="mx-auto w-full max-w-4xl">
              <div className="relative aspect-video overflow-hidden rounded-md border border-slate-300 bg-slate-950 shadow-lg">
                {cameraActive ? <video ref={videoRef} autoPlay muted playsInline aria-label="Hình ảnh trực tiếp từ camera" className="aspect-video w-full object-cover" onLoadedMetadata={() => setCameraReady(true)} /> : <div className="flex aspect-video items-center justify-center text-slate-400"><Camera aria-hidden="true" className="h-16 w-16" /></div>}
                {cameraActive ? <div aria-hidden="true" className="pointer-events-none absolute inset-x-1/4 inset-y-4 rounded-full border-2 border-dashed border-white/90 shadow-[0_0_0_999px_rgba(15,23,42,0.28)]" /> : null}
                {countdown !== null ? <div aria-live="assertive" className="absolute inset-0 flex items-center justify-center"><span className="flex h-20 w-20 items-center justify-center rounded-full bg-slate-950/80 text-4xl font-bold text-white">{countdown || '✓'}</span></div> : null}
                <p aria-live="assertive" className="absolute inset-x-3 bottom-3 rounded bg-slate-950/75 px-3 py-2 text-center text-xs font-medium text-white">{cameraActive ? captureInstruction : 'Camera chưa sẵn sàng'}</p>
                {localError ? <p className="absolute inset-x-3 top-3 rounded bg-red-700/90 px-3 py-2 text-center text-sm font-medium text-white" role="alert">{localError}</p> : null}
                {cameraActive && cameraReady && allConditionsPassed && capturePhase === 'IDLE' ? <Button className="absolute right-3 top-3" type="button" onClick={captureCamera}>Chụp ngay</Button> : null}
              </div>
          </div>
        )}
      </Modal>

      <ConfirmDialog
        confirmLabel={pendingConfirmation === 'REMOVE_PROFILE' ? 'Xóa hồ sơ' : 'Rút đồng ý'}
        isOpen={pendingConfirmation !== null}
        isPending={actions.remove.isPending || actions.consent.isPending}
        message={pendingConfirmation === 'REMOVE_PROFILE'
          ? 'Hồ sơ khuôn mặt hiện tại sẽ bị xóa.'
          : 'Rút đồng ý sẽ vô hiệu hóa việc sử dụng hồ sơ khuôn mặt để nhận diện.'}
        onCancel={() => setPendingConfirmation(null)}
        onConfirm={confirmFaceAction}
        title={pendingConfirmation === 'REMOVE_PROFILE' ? 'Xóa hồ sơ khuôn mặt?' : 'Rút lại đồng ý?'}
      />

      {!cameraDialogOpen && (localError || mutationError) ? <p className="mt-5 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-200" role="alert">{localError || errorMessage(mutationError)}</p> : null}
    </section>
  );
}
