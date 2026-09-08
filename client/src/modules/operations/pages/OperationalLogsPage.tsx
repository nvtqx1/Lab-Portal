import { Activity, Bot, ScanFace, ShieldCheck } from 'lucide-react';
import { FormEvent, useDeferredValue, useMemo, useState } from 'react';

import { getStoredRole } from '../../../shared/api';
import { Button, EmptyState, ErrorState, ResponsiveTable } from '../../../shared/components';
import { useOperationalLogs } from '../hooks';
import type { OperationalFilters, OperationalLogItem, OperationalLogKind } from '../types';

interface DisplayRow { id: number; createdAt: string; actor: string; scope: string; event: string; outcome: string; detail: string; }

function resultLabel(value: string) {
  return ({ SUCCESS: 'Thành công', FAILED: 'Thất bại', DENIED: 'Bị từ chối' } as Record<string, string>)[value] ?? value;
}

function failureLabel(value: string | null) {
  if (!value) return 'Không có lỗi';
  return ({ NO_MATCH: 'Không tìm thấy khuôn mặt khớp', NO_FACE: 'Không phát hiện khuôn mặt', MULTIPLE_FACES: 'Phát hiện nhiều khuôn mặt', LOW_QUALITY: 'Hình ảnh chưa đạt chất lượng', SPOOF_DETECTED: 'Không xác minh được khuôn mặt thật', SERVICE_ERROR: 'Dịch vụ nhận diện không khả dụng' } as Record<string, string>)[value] ?? value;
}

function displayRow(kind: OperationalLogKind, item: OperationalLogItem): DisplayRow {
  if (kind === 'ai-usage' && 'promptTokens' in item) return {
    id: item.id, createdAt: item.createdAt, actor: `User #${item.userId}`,
    scope: item.labId ? `PTN #${item.labId}` : item.module,
    event: item.assistantKey, outcome: item.status,
    detail: `${item.promptTokens + item.completionTokens} tokens${item.errorRecorded ? ' · Có lỗi' : ''}`,
  };
  if (kind === 'ai-actions' && 'actionType' in item) return {
    id: item.id, createdAt: item.createdAt, actor: `User #${item.requestedById}`,
    scope: `${item.resourceType} #${item.resourceId}`, event: item.actionType,
    outcome: item.status, detail: `Execution: ${item.executionStatus}`,
  };
  const face = item as Extract<OperationalLogItem, { bookingId: number }>;
  return { id: face.id, createdAt: face.createdAt, actor: face.studentName ?? `Người dùng #${face.userId}`, scope: `PTN #${face.labId}`, event: `Nhận diện khuôn mặt · Lượt đặt #${face.bookingId}`, outcome: resultLabel(face.result), detail: failureLabel(face.failureReason) };
}

const tabs: Array<{ kind: OperationalLogKind; label: string }> = [
  { kind: 'ai-usage', label: 'Sử dụng AI' }, { kind: 'ai-actions', label: 'Thao tác AI' }, { kind: 'face-checkins', label: 'Nhận diện khuôn mặt' },
];

export function OperationalLogsPage() {
  const isAdmin = getStoredRole()?.replace(/^ROLE_/, '') === 'ADMIN';
  const availableTabs = isAdmin ? tabs : tabs.filter((tab) => tab.kind === 'face-checkins');
  const [kind, setKind] = useState<OperationalLogKind>(availableTabs[0].kind);
  const [page, setPage] = useState(0);
  const [inputs, setInputs] = useState({ userId: '', labId: '', resourceId: '', module: '', assistantKey: '', resourceType: '', result: '', from: '', to: '' });
  const [filters, setFilters] = useState<OperationalFilters>({});
  const deferredFilters = useDeferredValue(filters);
  const query = useOperationalLogs(kind, deferredFilters, page);
  const rows = useMemo(() => query.data?.items.map((item) => displayRow(kind, item)) ?? [], [kind, query.data]);

  const submit = (event: FormEvent) => {
    event.preventDefault(); setPage(0);
    const commonFilters: OperationalFilters = {
      ...(Number(inputs.userId) > 0 ? { userId: Number(inputs.userId) } : {}),
      ...(inputs.from ? { from: new Date(`${inputs.from}T00:00:00`).toISOString() } : {}),
      ...(inputs.to ? { to: new Date(`${inputs.to}T23:59:59`).toISOString() } : {}),
    };
    if (kind === 'ai-usage') {
      setFilters({ ...commonFilters, ...(Number(inputs.labId) > 0 ? { labId: Number(inputs.labId) } : {}), ...(inputs.module.trim() ? { module: inputs.module.trim() } : {}) });
    } else if (kind === 'ai-actions') {
      setFilters({ ...commonFilters, ...(Number(inputs.resourceId) > 0 ? { resourceId: Number(inputs.resourceId) } : {}), ...(inputs.assistantKey ? { assistantKey: inputs.assistantKey } : {}), ...(inputs.resourceType ? { resourceType: inputs.resourceType } : {}) });
    } else {
      setFilters({ ...commonFilters, ...(Number(inputs.labId) > 0 ? { labId: Number(inputs.labId) } : {}), ...(Number(inputs.resourceId) > 0 ? { bookingId: Number(inputs.resourceId) } : {}), ...(inputs.result ? { result: inputs.result } : {}) });
    }
  };
  const reset = () => { setInputs({ userId: '', labId: '', resourceId: '', module: '', assistantKey: '', resourceType: '', result: '', from: '', to: '' }); setFilters({}); setPage(0); };

  return (
    <section className="mx-auto max-w-7xl">
      <header className="mb-5"><p className="flex items-center gap-2 text-sm font-semibold uppercase tracking-wide text-slate-400"><Activity aria-hidden="true" className="h-4 w-4" /> Bằng chứng vận hành</p><h1 className="mt-1 text-2xl font-semibold text-slate-950 dark:text-white">Nhật ký vận hành</h1><p className="mt-2 text-sm text-slate-600 dark:text-slate-300">Theo dõi metadata đã giới hạn; không hiển thị prompt, ảnh, embedding, token hoặc thông tin bí mật.</p></header>
      <div className="mb-5 flex flex-wrap gap-2" role="tablist" aria-label="Loại nhật ký">{availableTabs.map((tab) => <button aria-selected={kind === tab.kind} className={['min-h-11 rounded-md px-4 text-sm font-semibold transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slate-500', kind === tab.kind ? 'bg-slate-900 text-white dark:bg-white dark:text-slate-950' : 'border border-slate-300 bg-white text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200'].join(' ')} key={tab.kind} role="tab" type="button" onClick={() => { setKind(tab.kind); setPage(0); setFilters({}); }}>{tab.kind === 'face-checkins' ? <ScanFace aria-hidden="true" className="mr-2 inline h-4 w-4" /> : <Bot aria-hidden="true" className="mr-2 inline h-4 w-4" />}{tab.label}</button>)}</div>
      <form className="mb-5 grid gap-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm sm:grid-cols-2 lg:grid-cols-6 dark:border-slate-800 dark:bg-slate-900" onSubmit={submit}>
        <FilterInput label="Mã người dùng" type="number" value={inputs.userId} onChange={(value) => setInputs((current) => ({ ...current, userId: value }))} />
        {kind !== 'ai-actions' ? <FilterInput label="Mã PTN" type="number" value={inputs.labId} onChange={(value) => setInputs((current) => ({ ...current, labId: value }))} /> : null}
        {kind !== 'ai-usage' ? <FilterInput label={kind === 'face-checkins' ? 'Mã lượt đặt' : 'Mã tài nguyên'} type="number" value={inputs.resourceId} onChange={(value) => setInputs((current) => ({ ...current, resourceId: value }))} /> : null}
        {kind === 'ai-usage' ? <FilterInput label="Phân hệ" value={inputs.module} onChange={(value) => setInputs((current) => ({ ...current, module: value }))} /> : null}
        {kind === 'ai-actions' ? <FilterSelect label="Trợ lý" value={inputs.assistantKey} options={['ADMIN_ASSISTANT', 'LAB_ASSISTANT', 'RESEARCH_ASSISTANT']} onChange={(value) => setInputs((current) => ({ ...current, assistantKey: value }))} /> : null}
        {kind === 'ai-actions' ? <FilterSelect label="Loại tài nguyên" value={inputs.resourceType} options={['SYSTEM', 'AUDIT_LOG', 'USER_ACCOUNT', 'SYSTEM_CONFIG', 'LABORATORY', 'TIME_SLOT', 'BOOKING', 'PROJECT', 'GROUP', 'TASK', 'REPORT']} onChange={(value) => setInputs((current) => ({ ...current, resourceType: value }))} /> : null}
        {kind === 'face-checkins' ? <FilterSelect label="Kết quả" value={inputs.result} options={['SUCCESS', 'FAILED', 'DENIED']} onChange={(value) => setInputs((current) => ({ ...current, result: value }))} /> : null}
        <FilterInput label="Từ ngày" type="date" value={inputs.from} onChange={(value) => setInputs((current) => ({ ...current, from: value }))} />
        <FilterInput label="Đến ngày" type="date" value={inputs.to} onChange={(value) => setInputs((current) => ({ ...current, to: value }))} />
        <div className="flex items-end gap-2 sm:col-span-2 lg:col-span-6 lg:justify-end"><Button variant="outline" onClick={reset}>Đặt lại</Button><Button type="submit">Lọc nhật ký</Button></div>
      </form>
      {query.isLoading ? <div aria-busy="true" className="space-y-2 rounded-lg border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">{[0,1,2,3].map((value) => <div className="h-12 animate-pulse rounded bg-slate-100 dark:bg-slate-800" key={value} />)}</div> : query.isError ? <ErrorState className="dark:border-red-900 dark:bg-red-950/40 dark:text-red-200" onRetry={() => void query.refetch()}>Không thể tải nhật ký vận hành.</ErrorState> : rows.length === 0 ? <EmptyState className="dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300">Không có dữ liệu phù hợp với bộ lọc.</EmptyState> : <ResponsiveTable className="border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900"><table className="w-full min-w-[900px] text-left text-sm"><thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase text-slate-500 dark:border-slate-800 dark:bg-slate-950 dark:text-slate-400"><tr><th className="px-4 py-3">Thời gian</th><th className="px-4 py-3">Người dùng</th><th className="px-4 py-3">Phạm vi</th><th className="px-4 py-3">Sự kiện</th><th className="px-4 py-3">Kết quả</th><th className="px-4 py-3">Chi tiết an toàn</th></tr></thead><tbody className="divide-y divide-slate-200 dark:divide-slate-800">{rows.map((row) => <tr className="text-slate-700 hover:bg-slate-50 dark:text-slate-200 dark:hover:bg-slate-800/60" key={row.id}><td className="whitespace-nowrap px-4 py-3">{new Date(row.createdAt).toLocaleString('vi-VN')}</td><td className="px-4 py-3">{row.actor}</td><td className="px-4 py-3">{row.scope}</td><td className="px-4 py-3 font-medium">{row.event}</td><td className="px-4 py-3"><span className="inline-flex rounded-full bg-slate-100 px-2 py-1 text-xs font-semibold dark:bg-slate-800">{row.outcome}</span></td><td className="px-4 py-3">{row.detail}</td></tr>)}</tbody></table></ResponsiveTable>}
      {query.data && query.data.totalPages > 1 ? <nav aria-label="Phân trang nhật ký" className="mt-4 flex items-center justify-between"><p className="text-sm text-slate-500 dark:text-slate-400">Trang {query.data.page + 1}/{query.data.totalPages} · {query.data.totalElements} bản ghi</p><div className="flex gap-2"><Button disabled={page === 0} size="sm" variant="outline" onClick={() => setPage((value) => value - 1)}>Trước</Button><Button disabled={page >= query.data.totalPages - 1} size="sm" variant="outline" onClick={() => setPage((value) => value + 1)}>Sau</Button></div></nav> : null}
      <p className="mt-5 flex items-start gap-2 text-xs leading-5 text-slate-500 dark:text-slate-400"><ShieldCheck aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0" /> Lab Manager chỉ xem face check-in trong phạm vi PTN được quản lý; AI logs chỉ dành cho Admin.</p>
    </section>
  );
}

function FilterInput({ label, onChange, type = 'text', value }: { label: string; onChange: (value: string) => void; type?: string; value: string }) { const id = `operation-${label.toLowerCase().replace(/\s+/g, '-')}`; return <label className="block text-xs font-semibold text-slate-600 dark:text-slate-300" htmlFor={id}>{label}<input id={id} className="mt-1 min-h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-base text-slate-950 dark:border-slate-700 dark:bg-slate-950 dark:text-white" min={type === 'number' ? 1 : undefined} type={type} value={value} onChange={(event) => onChange(event.target.value)} /></label>; }
function FilterSelect({ label, onChange, options, value }: { label: string; onChange: (value: string) => void; options: string[]; value: string }) { const id = `operation-${label.toLowerCase().replace(/\s+/g, '-')}`; return <label className="block text-xs font-semibold text-slate-600 dark:text-slate-300" htmlFor={id}>{label}<select id={id} className="mt-1 min-h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-base text-slate-950 dark:border-slate-700 dark:bg-slate-950 dark:text-white" value={value} onChange={(event) => onChange(event.target.value)}><option value="">Tất cả</option>{options.map((option) => <option key={option} value={option}>{option}</option>)}</select></label>; }
