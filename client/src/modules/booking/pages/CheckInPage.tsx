import type { IScannerControls } from '@zxing/browser';
import { useQuery } from '@tanstack/react-query';
import axios from 'axios';
import { CheckCircle2, Clock3, Keyboard, QrCode, ScanFace } from 'lucide-react';
import { FormEvent, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';

import type { Response } from '../../../shared/types';
import { queryKeys } from '../../../shared/api';
import { Button, EmptyState, ErrorState, Modal, ResponsiveTable } from '../../../shared/components';
import { cameraErrorMessage } from '../../../shared/utils';
import { useOperationalLogs } from '../../operations/hooks';
import type { FaceCheckinLog } from '../../operations/types';
import { ManagerFaceCheckinPanel } from '../../face/components/ManagerFaceCheckinPanel';
import { getFaceCheckinCandidates } from '../../face/api';
import type { BookingResponse } from '../api';
import { useConfirmCheckIn, useManualCheckIn, usePendingCheckinQrRequests, useReviewCheckinQrRequest } from '../hooks';

type CheckinTab = 'QR' | 'FACE' | 'MANUAL';

function getErrorMessage(error: unknown) {
  if (axios.isAxiosError(error)) {
    const response = error.response?.data as Partial<Response<unknown>> | undefined;
    return response?.message ?? response?.errors?.[0] ?? 'Không thể xác nhận có mặt. Vui lòng thử lại sau.';
  }
  return 'Không thể xác nhận có mặt. Vui lòng thử lại sau.';
}

export function CheckInPage() {
  const [activeTab, setActiveTab] = useState<CheckinTab>('FACE');
  const [token, setToken] = useState('');
  const [error, setError] = useState('');
  const [cameraError, setCameraError] = useState('');
  const [isCameraActive, setIsCameraActive] = useState(false);
  const [qrCameraDialogOpen, setQrCameraDialogOpen] = useState(false);
  const [lastSuccess, setLastSuccess] = useState<{
    method: 'Nhận diện khuôn mặt' | 'QR' | 'Thủ công';
    bookingId: number;
    studentName?: string | null;
    booking?: BookingResponse;
  } | null>(null);
  const confirmCheckIn = useConfirmCheckIn();
  const manualCheckIn = useManualCheckIn();
  const qrRequests = usePendingCheckinQrRequests(activeTab === 'QR');
  const reviewQrRequest = useReviewCheckinQrRequest();
  const faceLogs = useOperationalLogs('face-checkins', {}, 0, 50);
  const candidates = useQuery({
    queryKey: queryKeys.face.checkinCandidates,
    queryFn: getFaceCheckinCandidates,
  });
  const [manualBookingId, setManualBookingId] = useState('');
  const [manualReason, setManualReason] = useState('');
  const [manualError, setManualError] = useState('');
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const scannerControlsRef = useRef<IScannerControls | null>(null);
  const scannerSessionRef = useRef(0);
  const handledTokenRef = useRef(false);

  const stopCamera = () => {
    scannerSessionRef.current += 1;
    scannerControlsRef.current?.stop();
    scannerControlsRef.current = null;
    const video = videoRef.current;
    if (video?.srcObject instanceof MediaStream) {
      video.srcObject.getTracks().forEach((track) => track.stop());
      video.srcObject = null;
    }
    setIsCameraActive(false);
  };

  useEffect(() => stopCamera, []);

  const confirmToken = (value: string) => {
    const normalizedToken = value.trim();
    if (!normalizedToken) {
      setError('Vui lòng nhập mã QR hoặc token check-in.');
      return;
    }

    setError('');
    setLastSuccess(null);
    confirmCheckIn.mutate(normalizedToken, {
      onSuccess: (result) => {
        setLastSuccess({ method: 'QR', bookingId: result.booking.id, booking: result.booking });
        setToken('');
        handledTokenRef.current = false;
      },
      onError: (submitError) => {
        setError(getErrorMessage(submitError));
        handledTokenRef.current = false;
      },
    });
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    confirmToken(token);
  };

  const startCamera = async () => {
    setCameraError('');
    setError('');
    setLastSuccess(null);
    handledTokenRef.current = false;

    if (!navigator.mediaDevices?.getUserMedia) {
      setCameraError('Trình duyệt không cung cấp quyền truy cập camera. Vui lòng kiểm tra HTTPS/localhost hoặc nhập token thủ công.');
      return;
    }

    try {
      setQrCameraDialogOpen(true);
      setIsCameraActive(true);
      await new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve()));
      const session = scannerSessionRef.current + 1;
      scannerSessionRef.current = session;
      const video = videoRef.current;
      if (!video) {
        setCameraError('Không tìm thấy vùng hiển thị camera. Hãy tải lại trang và thử lại.');
        setIsCameraActive(false);
        return;
      }
      const { BrowserQRCodeReader } = await import('@zxing/browser');
      const reader = new BrowserQRCodeReader(undefined, { delayBetweenScanAttempts: 200 });
      const controls = await reader.decodeFromConstraints(
        { video: { facingMode: { ideal: 'environment' } }, audio: false },
        video,
        (result, _scanError, activeControls) => {
          if (scannerSessionRef.current !== session) {
            activeControls.stop();
            return;
          }
          const scannedToken = result?.getText().trim();
          if (!scannedToken || handledTokenRef.current) return;
          handledTokenRef.current = true;
          setToken(scannedToken);
          scannerSessionRef.current += 1;
          activeControls.stop();
          scannerControlsRef.current = null;
          setIsCameraActive(false);
          confirmToken(scannedToken);
        },
      );
      if (scannerSessionRef.current !== session) {
        controls.stop();
        return;
      }
      scannerControlsRef.current = controls;
    } catch (cameraError) {
      setCameraError(`${cameraErrorMessage(cameraError)} Bạn có thể nhập token thủ công.`);
      stopCamera();
    }
  };

  const closeQrCameraDialog = () => {
    if (confirmCheckIn.isPending) return;
    stopCamera();
    setQrCameraDialogOpen(false);
    setCameraError('');
  };

  const handleManualCheckin = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (Number(manualBookingId) <= 0 || !manualReason.trim()) {
      setManualError('Vui lòng chọn thành viên, ca sử dụng và nhập lý do xác nhận thủ công.');
      return;
    }
    setManualError('');
    manualCheckIn.mutate(
      { bookingId: Number(manualBookingId), reason: manualReason.trim() },
      { onSuccess: (result) => {
        setLastSuccess({ method: 'Thủ công', bookingId: result.booking.id, booking: result.booking });
        setManualBookingId('');
        setManualReason('');
      } },
    );
  };

  const faceAttempts = (faceLogs.data?.items ?? []).filter(
    (item): item is FaceCheckinLog => 'method' in item && item.method === 'FACE',
  );

  return (
    <div className="mx-auto max-w-7xl">
      <section className="rounded-xl bg-white p-5 shadow-sm ring-1 ring-slate-200 dark:bg-slate-900 dark:ring-slate-800 sm:p-6">
        <div>
          <p className="text-sm font-semibold text-blue-700 dark:text-blue-300">Trạm điểm danh của quản lý PTN</p>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight text-slate-950 dark:text-white">Check-in thành viên theo ca</h1>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-slate-600 dark:text-slate-300">
            Nhận diện khuôn mặt là phương thức chính; có thể quét QR dự phòng hoặc xác nhận thủ công khi cần.
          </p>
        </div>

        <div className="mt-5 flex items-start gap-3 rounded-lg border border-blue-200 bg-blue-50 p-4 text-sm text-blue-900 dark:border-blue-900 dark:bg-blue-950/40 dark:text-blue-100">
          <Clock3 aria-hidden="true" className="mt-0.5 h-5 w-5 shrink-0" />
          <div><p className="font-semibold">Cửa sổ check-in: trước 5 phút đến sau 10 phút</p><p className="mt-1 text-blue-800 dark:text-blue-200">Chỉ các booking đã được duyệt trong khoảng thời gian này mới xuất hiện để xác nhận.</p></div>
        </div>

        <div className="mt-6 flex flex-wrap gap-2" role="tablist" aria-label="Phương thức quản lý check-in">
          {([
            { id: 'FACE' as const, label: 'Nhận diện khuôn mặt', icon: ScanFace },
            { id: 'QR' as const, label: 'Quét QR', icon: QrCode },
            { id: 'MANUAL' as const, label: 'Thủ công', icon: Keyboard },
          ]).map(({ id, label, icon: Icon }) => <button aria-selected={activeTab === id} className={activeTab === id ? 'flex min-h-11 items-center gap-2 rounded-md bg-slate-900 px-4 text-sm font-semibold text-white shadow-sm dark:bg-white dark:text-slate-950' : 'flex min-h-11 items-center gap-2 rounded-md border border-slate-300 px-4 text-sm font-semibold text-slate-700 transition hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800'} key={id} role="tab" type="button" onClick={() => { stopCamera(); setActiveTab(id); }}><Icon aria-hidden="true" className="h-4 w-4" />{label}</button>)}
        </div>

        {lastSuccess ? (
          <div className="mt-5 flex items-start gap-3 rounded-lg border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-900 dark:border-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-100" role="status">
            <CheckCircle2 aria-hidden="true" className="mt-0.5 h-5 w-5 shrink-0" />
            <div><p className="font-semibold">Xác nhận có mặt bằng {lastSuccess.method} thành công</p><p className="mt-1">Lượt đặt #{lastSuccess.bookingId}{lastSuccess.studentName ? ` · ${lastSuccess.studentName}` : lastSuccess.booking ? ` · ${lastSuccess.booking.studentName ?? lastSuccess.booking.studentEmail ?? `Người dùng #${lastSuccess.booking.userId}`}` : ''} đã chuyển sang trạng thái “Đã xác nhận có mặt”.</p></div>
          </div>
        ) : null}

        {activeTab === 'QR' ? <><section className="mt-6 rounded-lg border border-blue-200 bg-blue-50 p-4">
          <h2 className="font-semibold text-slate-950">Yêu cầu QR đang chờ duyệt</h2>
          <p className="mt-1 text-sm text-slate-600">Chỉ yêu cầu thuộc PTN bạn quản lý mới xuất hiện tại đây.</p>
          {qrRequests.isLoading ? <p className="mt-4 text-sm text-slate-500">Đang tải yêu cầu...</p> : qrRequests.isError ? <ErrorState className="mt-4" onRetry={() => void qrRequests.refetch()}>Không thể tải yêu cầu QR.</ErrorState> : !qrRequests.data?.length ? <EmptyState className="mt-4">Không có yêu cầu QR đang chờ.</EmptyState> : (
            <div className="mt-4 space-y-3">{qrRequests.data.map((request) => (
              <article className="rounded-md border border-blue-200 bg-white p-4" key={request.requestId}>
                <p className="font-semibold text-slate-900">{request.studentName ?? `Người dùng #${request.studentId}`} · Lượt đặt #{request.bookingId}</p>
                <p className="mt-1 text-sm text-slate-700">Lý do: {request.reason}</p>
                <div className="mt-3 flex gap-2">
                  <Button disabled={reviewQrRequest.isPending} onClick={() => reviewQrRequest.mutate({ requestId: request.requestId, approved: true })}>Duyệt và cấp QR</Button>
                  <Button disabled={reviewQrRequest.isPending} variant="danger" onClick={() => reviewQrRequest.mutate({ requestId: request.requestId, approved: false })}>Từ chối</Button>
                </div>
              </article>
            ))}</div>
          )}
        </section><div className="mt-6 rounded-lg border border-slate-200 bg-slate-50 p-4">
          <p className="text-sm text-slate-600">Camera sẽ mở trong cửa sổ giữa màn hình. Đưa mã QR của sinh viên vào giữa khung để hệ thống tự đọc và xác nhận một lần.</p>
          <div className="mt-4 flex flex-col gap-2 sm:flex-row">
            <button
              className="rounded-md bg-slate-900 px-4 py-2 text-sm font-semibold text-white disabled:bg-slate-400"
              disabled={confirmCheckIn.isPending || isCameraActive}
              type="button"
              onClick={startCamera}
            >
              {isCameraActive ? 'Đang quét QR...' : 'Bật camera quét QR'}
            </button>
          </div>
          {cameraError && !qrCameraDialogOpen ? <p className="mt-3 text-sm font-medium text-amber-700">{cameraError}</p> : null}
        </div>

        <form className="mt-6 space-y-4" onSubmit={handleSubmit}>
          <label className="block text-sm font-semibold text-slate-700" htmlFor="checkin-token">
            Token check-in dự phòng
          </label>
          <textarea
            id="checkin-token"
            className="min-h-24 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-slate-900 focus:ring-2 focus:ring-slate-200"
            placeholder="Dán token từ mã QR của sinh viên nếu camera không quét được"
            value={token}
            onChange={(event) => setToken(event.target.value)}
          />

          {error ? (
            <div className="rounded-md border border-red-200 bg-red-50 px-3 py-3 text-sm font-semibold text-red-700">
              {error}
            </div>
          ) : null}

          <button
            className="w-full rounded-md bg-slate-900 px-4 py-2 text-sm font-semibold text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-400 sm:w-auto"
            disabled={confirmCheckIn.isPending}
            type="submit"
          >
            {confirmCheckIn.isPending ? 'Đang xác nhận...' : 'Xác nhận'}
          </button>
        </form>
        </> : null}

        {activeTab === 'FACE' ? <section className="mt-6" role="tabpanel">
          <div className="flex flex-wrap items-center justify-between gap-3"><div><h2 className="text-lg font-semibold text-slate-950">Nhận diện khuôn mặt theo ca sử dụng</h2><p className="mt-1 text-sm text-slate-600">Quản lý chỉ cần chọn ca sử dụng. Camera sẽ tự nhận diện trong danh sách sinh viên đã đăng ký ca đó và xác nhận có mặt.</p></div><div className="flex gap-2"><Button type="button" variant="outline" onClick={() => void faceLogs.refetch()}>Làm mới</Button><Link className="inline-flex min-h-11 items-center rounded-md border border-slate-300 px-4 text-sm font-semibold text-slate-700" to="/app/operational-logs">Xem toàn bộ nhật ký</Link></div></div>
          <ManagerFaceCheckinPanel onCompleted={(result) => { if (result.bookingId !== null) setLastSuccess({ method: 'Nhận diện khuôn mặt', bookingId: result.bookingId, studentName: result.studentName }); void faceLogs.refetch(); }} />
          <h3 className="mt-8 text-base font-semibold text-slate-950">Nhật ký nhận diện gần đây</h3>
          {faceLogs.isLoading ? <div className="mt-4 h-28 animate-pulse rounded bg-slate-100" /> : faceLogs.isError ? <ErrorState className="mt-4" onRetry={() => void faceLogs.refetch()}>Không thể tải nhật ký nhận diện.</ErrorState> : faceAttempts.length === 0 ? <EmptyState className="mt-4">Chưa có lượt nhận diện nào trong PTN đang quản lý.</EmptyState> : <ResponsiveTable className="mt-4"><table className="w-full min-w-[720px] text-left text-sm"><thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase text-slate-500"><tr><th className="px-4 py-3">Thời gian</th><th className="px-4 py-3">Lượt đặt</th><th className="px-4 py-3">Sinh viên</th><th className="px-4 py-3">Kết quả</th><th className="px-4 py-3">Chi tiết</th></tr></thead><tbody className="divide-y divide-slate-200">{faceAttempts.map((attempt) => <tr key={attempt.id}><td className="whitespace-nowrap px-4 py-3">{new Date(attempt.createdAt).toLocaleString('vi-VN')}</td><td className="px-4 py-3 font-medium">#{attempt.bookingId}</td><td className="px-4 py-3">{attempt.studentName ?? `Người dùng #${attempt.userId}`}</td><td className="px-4 py-3">{attempt.result}</td><td className="px-4 py-3">{attempt.failureReason ?? 'Không có lỗi'}</td></tr>)}</tbody></table></ResponsiveTable>}
        </section> : null}

        {activeTab === 'MANUAL' ? <section className="mt-6 rounded-lg border border-amber-200 bg-amber-50 p-6 dark:border-amber-900 dark:bg-amber-950/30" role="tabpanel">
          <p className="text-sm font-semibold uppercase tracking-wide text-amber-700 dark:text-amber-300">Fallback có kiểm soát</p>
          <h2 className="mt-1 text-xl font-semibold text-slate-950 dark:text-white">Xác nhận thủ công</h2>
          <p className="mt-2 text-sm leading-6 text-slate-600 dark:text-slate-300">Chỉ sử dụng khi Face Check-in và QR không khả dụng. Lý do sẽ được lưu trong audit log.</p>
          {candidates.isError ? <ErrorState className="mt-5" onRetry={() => void candidates.refetch()}>Không thể tải booking trong cửa sổ check-in.</ErrorState> : null}
          <form className="mt-5 grid gap-4 sm:grid-cols-2" onSubmit={handleManualCheckin}>
            <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 sm:col-span-2" htmlFor="manual-booking-id">Thành viên và ca sử dụng<select id="manual-booking-id" className="mt-2 min-h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-base text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 disabled:cursor-wait disabled:opacity-60 dark:border-slate-700 dark:bg-slate-900 dark:text-white" disabled={candidates.isLoading || candidates.isError} value={manualBookingId} onChange={(event) => setManualBookingId(event.target.value)}><option value="">{candidates.isLoading ? 'Đang tải booking...' : 'Chọn booking trong cửa sổ check-in'}</option>{candidates.data?.map((candidate) => <option key={candidate.bookingId} value={candidate.bookingId}>{candidate.studentName ?? candidate.studentEmail} — {new Date(candidate.startTime).toLocaleString('vi-VN')}</option>)}</select></label>
            <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 sm:col-span-2" htmlFor="manual-checkin-reason">Lý do xác nhận thủ công<textarea id="manual-checkin-reason" className="mt-2 min-h-24 w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-base text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 dark:border-slate-700 dark:bg-slate-900 dark:text-white" maxLength={1000} value={manualReason} onChange={(event) => setManualReason(event.target.value)} /></label>
            {manualError ? <p className="text-sm font-medium text-red-700 sm:col-span-2" role="alert">{manualError}</p> : null}
            <Button className="sm:col-span-2 sm:justify-self-start" loading={manualCheckIn.isPending} loadingText="Đang xác nhận..." type="submit" variant="danger">Xác nhận fallback thủ công</Button>
          </form>
        </section> : null}
      </section>

      <Modal
        backdropBlur
        centered
        closeDisabled={confirmCheckIn.isPending}
        closeOnEscape
        isOpen={qrCameraDialogOpen}
        onClose={closeQrCameraDialog}
        size={lastSuccess?.method === 'QR' ? 'lg' : '2xl'}
        subtitle={lastSuccess?.method === 'QR' ? 'Kết quả đã được ghi nhận vào ca sử dụng.' : 'Đặt mã QR vào chính giữa khung hình.'}
        title={lastSuccess?.method === 'QR' ? 'Kết quả quét QR' : 'Camera quét QR check-in'}
      >
        {lastSuccess?.method === 'QR' ? (
          <div className="flex flex-col items-center py-8 text-center" role="status">
            <span className="flex h-16 w-16 items-center justify-center rounded-full bg-emerald-100 text-emerald-700"><CheckCircle2 aria-hidden="true" className="h-9 w-9" /></span>
            <h3 className="mt-4 text-xl font-semibold text-slate-950">Check-in thành công</h3>
            <p className="mt-2 text-sm text-slate-600">Lượt đặt #{lastSuccess.bookingId}{lastSuccess.booking ? ` · ${lastSuccess.booking.studentName ?? lastSuccess.booking.studentEmail ?? `Người dùng #${lastSuccess.booking.userId}`}` : ''} đã được xác nhận có mặt.</p>
            <Button className="mt-6" type="button" onClick={closeQrCameraDialog}>Hoàn tất</Button>
          </div>
        ) : (
          <div className="mx-auto w-full max-w-4xl">
            <div className="relative mx-auto aspect-video w-full overflow-hidden rounded-md bg-slate-950">
              <video ref={videoRef} autoPlay className="h-full w-full object-cover" muted playsInline aria-label="Camera quét mã QR check-in" />
              <div aria-hidden="true" className="pointer-events-none absolute left-1/2 top-1/2 aspect-square w-[58%] -translate-x-1/2 -translate-y-1/2 rounded-lg border-2 border-white shadow-[0_0_0_999px_rgba(15,23,42,0.35)]" />
              <p className="absolute inset-x-3 bottom-3 rounded bg-slate-950/80 px-3 py-2 text-center text-sm font-semibold text-white" aria-live="polite">{confirmCheckIn.isPending ? 'Đã đọc mã — đang xác nhận check-in...' : isCameraActive ? 'Đang tìm mã QR...' : 'Camera đã dừng'}</p>
              {cameraError || error ? <p className="absolute inset-x-3 top-3 rounded bg-red-700/90 px-3 py-2 text-center text-sm font-medium text-white" role="alert">{cameraError || error}</p> : null}
            </div>
          </div>
        )}
      </Modal>

    </div>
  );
}
