import axios from 'axios';
import { BookOpenCheck, FileUp, RefreshCw, ShieldAlert, Trash2 } from 'lucide-react';
import { FormEvent, useMemo, useState } from 'react';
import { useMutation } from '@tanstack/react-query';

import { getStoredRole, getStoredUser } from '../../../shared/api';
import { Button, ConfirmDialog } from '../../../shared/components';
import type { Response } from '../../../shared/types';
import { getManagedLabId } from '../../../shared/utils/membership';
import { ingestKnowledgeDocument, reindexKnowledgeDocument, revokeKnowledgeDocument } from '../api';
import type { KnowledgeDocumentRequest, KnowledgeDomain, KnowledgeVisibility } from '../types';

function getError(error: unknown) {
  if (axios.isAxiosError(error)) return (error.response?.data as Partial<Response<unknown>> | undefined)?.message ?? 'Không thể cập nhật kho tri thức.';
  return 'Không thể cập nhật kho tri thức.';
}

function visibilityOptions(domain: KnowledgeDomain): Array<{ value: KnowledgeVisibility; label: string }> {
  if (domain === 'ADMIN') return [{ value: 'ADMIN_ONLY', label: 'Chỉ quản trị viên' }];
  if (domain === 'LAB') return [
    { value: 'LAB_MEMBERS', label: 'Thành viên PTN' },
    { value: 'OWNER', label: 'Chủ sở hữu tài liệu' },
  ];
  return [
    { value: 'LAB_MEMBERS', label: 'Thành viên PTN' },
    { value: 'PROJECT_MEMBERS', label: 'Thành viên dự án' },
    { value: 'GROUP_MEMBERS', label: 'Thành viên nhóm' },
    { value: 'OWNER', label: 'Chủ sở hữu tài liệu' },
  ];
}

export function KnowledgePage() {
  const isAdmin = getStoredRole()?.replace(/^ROLE_/, '') === 'ADMIN';
  const managedLabId = getManagedLabId(getStoredUser());
  const [domain, setDomain] = useState<KnowledgeDomain>(isAdmin ? 'ADMIN' : 'LAB');
  const [visibility, setVisibility] = useState<KnowledgeVisibility>(isAdmin ? 'ADMIN_ONLY' : 'LAB_MEMBERS');
  const [resourceId, setResourceId] = useState('');
  const [version, setVersion] = useState('1');
  const [sourceType, setSourceType] = useState('POLICY');
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [projectId, setProjectId] = useState('');
  const [groupId, setGroupId] = useState('');
  const [documentId, setDocumentId] = useState('');
  const [error, setError] = useState('');
  const [revokeConfirmationOpen, setRevokeConfirmationOpen] = useState(false);
  const [lastResult, setLastResult] = useState<Awaited<ReturnType<typeof ingestKnowledgeDocument>> | null>(null);
  const allowedVisibilities = useMemo(() => visibilityOptions(domain), [domain]);

  const save = useMutation({
    mutationFn: (request: KnowledgeDocumentRequest) => documentId
      ? reindexKnowledgeDocument(Number(documentId), request)
      : ingestKnowledgeDocument(request),
    onSuccess: (result) => { setLastResult(result); setDocumentId(String(result.documentId)); },
  });
  const revoke = useMutation({
    mutationFn: () => revokeKnowledgeDocument(Number(documentId)),
    onSuccess: () => { setLastResult(null); setDocumentId(''); },
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const needsProject = visibility === 'PROJECT_MEMBERS' || visibility === 'GROUP_MEMBERS';
    const needsGroup = visibility === 'GROUP_MEMBERS';
    if (!resourceId.trim() || !title.trim() || !content.trim() || Number(version) <= 0) {
      setError('Vui lòng điền đầy đủ mã nguồn, phiên bản, tiêu đề và nội dung.'); return;
    }
    if (!/^[A-Z][A-Z0-9_]*$/.test(sourceType)) {
      setError('Loại nguồn phải viết hoa và chỉ gồm chữ, số hoặc dấu gạch dưới.'); return;
    }
    if (!isAdmin && !managedLabId) { setError('Tài khoản chưa được phân công quản lý PTN.'); return; }
    if (needsProject && Number(projectId) <= 0) { setError('Phạm vi này yêu cầu ID dự án.'); return; }
    if (needsGroup && Number(groupId) <= 0) { setError('Phạm vi này yêu cầu ID nhóm.'); return; }
    setError(''); setLastResult(null);
    save.mutate({
      domain, resourceId: resourceId.trim(), version: Number(version), sourceType,
      title: title.trim(), content: content.trim(), visibility,
      ...(!isAdmin ? { labId: managedLabId! } : {}),
      ...(needsProject ? { projectId: Number(projectId) } : {}),
      ...(needsGroup ? { groupId: Number(groupId) } : {}),
    });
  };

  return (
    <section className="mx-auto max-w-6xl">
      <header className="mb-6"><p className="flex items-center gap-2 text-sm font-semibold uppercase tracking-wide text-slate-500"><BookOpenCheck aria-hidden="true" className="h-4 w-4" /> Permission-aware RAG</p><h1 className="mt-1 text-2xl font-semibold text-slate-950 dark:text-white">Kho tri thức trợ lý</h1><p className="mt-2 max-w-3xl text-sm leading-6 text-slate-600 dark:text-slate-300">Nạp tài liệu dạng văn bản, gắn phạm vi truy cập và phiên bản. Chỉ những đoạn đã qua Spring permission filter mới được đưa vào câu trả lời AI.</p></header>
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_320px]">
        <form className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900" onSubmit={submit}>
          <div className="grid gap-4 sm:grid-cols-2">
            <Select label="Miền tri thức" value={domain} options={(isAdmin ? ['ADMIN'] : ['LAB', 'RESEARCH'])} onChange={(value) => { const next = value as KnowledgeDomain; setDomain(next); setVisibility(visibilityOptions(next)[0].value); }} />
            <Select label="Phạm vi hiển thị" value={visibility} options={allowedVisibilities.map((item) => item.value)} labels={Object.fromEntries(allowedVisibilities.map((item) => [item.value, item.label]))} onChange={(value) => setVisibility(value as KnowledgeVisibility)} />
            <TextInput label="Mã tài nguyên ổn định" value={resourceId} onChange={setResourceId} placeholder="lab-policy:2026" />
            <TextInput label="Loại nguồn" value={sourceType} onChange={(value) => setSourceType(value.toUpperCase())} placeholder="POLICY" />
            <NumberInput label="Phiên bản" value={version} onChange={setVersion} />
            {!isAdmin ? <TextInput label="ID PTN" value={String(managedLabId ?? '')} onChange={() => undefined} disabled /> : null}
            {(visibility === 'PROJECT_MEMBERS' || visibility === 'GROUP_MEMBERS') ? <NumberInput label="ID dự án" value={projectId} onChange={setProjectId} /> : null}
            {visibility === 'GROUP_MEMBERS' ? <NumberInput label="ID nhóm nghiên cứu" value={groupId} onChange={setGroupId} /> : null}
          </div>
          <TextInput className="mt-4" label="Tiêu đề tài liệu" value={title} onChange={setTitle} />
          <label className="mt-4 block text-sm font-semibold text-slate-700 dark:text-slate-200" htmlFor="knowledge-content">Nội dung văn bản<textarea id="knowledge-content" className="mt-2 min-h-64 w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-base leading-6 text-slate-950 dark:border-slate-700 dark:bg-slate-950 dark:text-white" maxLength={512000} value={content} onChange={(event) => setContent(event.target.value)} /></label>
          {error || save.isError ? <p className="mt-4 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-200" role="alert">{error || getError(save.error)}</p> : null}
          <Button className="mt-5" loading={save.isPending} loadingText="Đang lập chỉ mục..." type="submit"><FileUp aria-hidden="true" className="h-4 w-4" /> {documentId ? 'Reindex tài liệu' : 'Nạp tài liệu'}</Button>
        </form>

        <aside className="space-y-5">
          <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900"><h2 className="flex items-center gap-2 text-base font-semibold text-slate-950 dark:text-white"><RefreshCw aria-hidden="true" className="h-4 w-4" /> Bảo trì tài liệu</h2><label className="mt-4 block text-sm font-medium text-slate-700 dark:text-slate-200" htmlFor="knowledge-document-id">Document ID<input id="knowledge-document-id" className="mt-2 min-h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-base dark:border-slate-700 dark:bg-slate-950 dark:text-white" min={1} type="number" value={documentId} onChange={(event) => setDocumentId(event.target.value)} /></label><p className="mt-2 text-xs leading-5 text-slate-500 dark:text-slate-400">Nhập ID để reindex bằng form bên trái hoặc thu hồi quyền truy cập.</p><Button className="mt-4 w-full" disabled={Number(documentId) <= 0} loading={revoke.isPending} variant="danger" onClick={() => setRevokeConfirmationOpen(true)}><Trash2 aria-hidden="true" className="h-4 w-4" /> Thu hồi tài liệu</Button>{revoke.isError ? <p className="mt-3 text-sm text-red-700 dark:text-red-300" role="alert">{getError(revoke.error)}</p> : null}</section>
          {lastResult ? <section className="rounded-lg border border-emerald-200 bg-emerald-50 p-5 text-sm text-emerald-900 dark:border-emerald-900 dark:bg-emerald-950/30 dark:text-emerald-200"><h2 className="font-semibold">Đã lập chỉ mục thành công</h2><dl className="mt-3 grid grid-cols-2 gap-2"><dt>Document ID</dt><dd className="font-semibold text-right">{lastResult.documentId}</dd><dt>Namespace</dt><dd className="font-semibold text-right">{lastResult.namespace}</dd><dt>Số chunk</dt><dd className="font-semibold text-right">{lastResult.chunkCount}</dd><dt>Phiên bản</dt><dd className="font-semibold text-right">{lastResult.version}</dd></dl></section> : null}
          <section className="rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200"><p className="flex gap-2 font-semibold"><ShieldAlert aria-hidden="true" className="h-5 w-5 shrink-0" /> Tài liệu không phải chỉ dẫn hệ thống</p><p className="mt-2 leading-6">Nội dung trong tài liệu không thể ghi đè policy, quyền người dùng hoặc quy tắc an toàn của trợ lý.</p></section>
        </aside>
      </div>
      <ConfirmDialog
        confirmLabel="Thu hồi"
        isOpen={revokeConfirmationOpen}
        isPending={revoke.isPending}
        message="Tài liệu và toàn bộ chunk liên quan sẽ bị thu hồi quyền truy cập."
        onCancel={() => setRevokeConfirmationOpen(false)}
        onConfirm={() => revoke.mutate(undefined, { onSuccess: () => setRevokeConfirmationOpen(false) })}
        title="Thu hồi tài liệu?"
      />
    </section>
  );
}

function TextInput({ className = '', disabled = false, label, onChange, placeholder, value }: { className?: string; disabled?: boolean; label: string; onChange: (value: string) => void; placeholder?: string; value: string }) { const id = `knowledge-${label.toLowerCase().replace(/\s+/g, '-')}`; return <label className={`block text-sm font-semibold text-slate-700 dark:text-slate-200 ${className}`} htmlFor={id}>{label}<input id={id} className="mt-2 min-h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-base text-slate-950 disabled:bg-slate-100 dark:border-slate-700 dark:bg-slate-950 dark:text-white dark:disabled:bg-slate-800" disabled={disabled} placeholder={placeholder} value={value} onChange={(event) => onChange(event.target.value)} /></label>; }
function NumberInput({ label, onChange, value }: { label: string; onChange: (value: string) => void; value: string }) { return <TextInput label={label} value={value} onChange={onChange} />; }
function Select({ label, labels = {}, onChange, options, value }: { label: string; labels?: Record<string, string>; onChange: (value: string) => void; options: string[]; value: string }) { const id = `knowledge-${label.toLowerCase().replace(/\s+/g, '-')}`; return <label className="block text-sm font-semibold text-slate-700 dark:text-slate-200" htmlFor={id}>{label}<select id={id} className="mt-2 min-h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-base dark:border-slate-700 dark:bg-slate-950 dark:text-white" value={value} onChange={(event) => onChange(event.target.value)}>{options.map((option) => <option key={option} value={option}>{labels[option] ?? option}</option>)}</select></label>; }
