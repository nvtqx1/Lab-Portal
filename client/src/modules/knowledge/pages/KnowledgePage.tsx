import axios from 'axios';
import { BookOpenCheck, FileUp, RefreshCw, ShieldAlert, Trash2 } from 'lucide-react';
import { FormEvent, useMemo, useState } from 'react';
import { useMutation } from '@tanstack/react-query';

import { getStoredRole, getStoredUser } from '../../../shared/api';
import { Button, ConfirmDialog } from '../../../shared/components';
import type { Response } from '../../../shared/types';
import { getManagedLabId, getManagedLabName } from '../../../shared/utils/membership';
import { useGroupsByTopic, useResearchProjectsByLab, useResearchTopicsByLab } from '../../research/hooks';
import { useCurrentUser } from '../../user/hooks';
import { ingestKnowledgeDocument, reindexKnowledgeDocument, revokeKnowledgeDocument } from '../api';
import type { KnowledgeDocumentRequest, KnowledgeDomain, KnowledgeVisibility } from '../types';

function getError(error: unknown) {
  if (axios.isAxiosError(error)) {
    const message = (error.response?.data as Partial<Response<unknown>> | undefined)?.message;
    if (message === 'RAG reindex must preserve permission metadata and increase version') {
      return 'Khi cập nhật, hãy giữ nguyên mã tài nguyên và phạm vi hiển thị, đồng thời tăng phiên bản.';
    }
    if (message === 'An active RAG document already exists for the resource') {
      return 'Mã tài nguyên này đã tồn tại. Hãy cập nhật tài liệu hiện có hoặc sử dụng mã tài nguyên khác.';
    }
    if (message === 'RAG document not found') {
      return 'Không tìm thấy tài liệu hoặc tài liệu đã được thu hồi.';
    }
    return 'Không thể cập nhật kho tri thức. Vui lòng kiểm tra dữ liệu và thử lại.';
  }
  return 'Không thể cập nhật kho tri thức.';
}

const managerScopeOptions: Array<{ value: KnowledgeVisibility; label: string }> = [
  { value: 'LAB_MEMBERS', label: 'Toàn bộ thành viên PTN' },
  { value: 'PROJECT_MEMBERS', label: 'Thành viên một dự án nghiên cứu' },
  { value: 'GROUP_MEMBERS', label: 'Thành viên một nhóm nghiên cứu' },
  { value: 'OWNER', label: 'Chỉ mình tôi' },
];

export function KnowledgePage() {
  const isAdmin = getStoredRole()?.replace(/^ROLE_/, '') === 'ADMIN';
  const { data: currentUser } = useCurrentUser();
  const scopedUser = currentUser ?? getStoredUser();
  const managedLabId = getManagedLabId(scopedUser);
  const managedLabName = getManagedLabName(scopedUser);
  const projectsQuery = useResearchProjectsByLab(!isAdmin ? managedLabId : null);
  const topicsQuery = useResearchTopicsByLab(!isAdmin ? managedLabId : null);
  const [domain, setDomain] = useState<KnowledgeDomain>(isAdmin ? 'ADMIN' : 'LAB');
  const [visibility, setVisibility] = useState<KnowledgeVisibility>(isAdmin ? 'ADMIN_ONLY' : 'LAB_MEMBERS');
  const [resourceId, setResourceId] = useState('');
  const [version, setVersion] = useState('1');
  const [sourceType, setSourceType] = useState('POLICY');
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [projectId, setProjectId] = useState('');
  const [topicId, setTopicId] = useState('');
  const [groupId, setGroupId] = useState('');
  const [documentId, setDocumentId] = useState('');
  const [error, setError] = useState('');
  const [revokeConfirmationOpen, setRevokeConfirmationOpen] = useState(false);
  const [lastResult, setLastResult] = useState<Awaited<ReturnType<typeof ingestKnowledgeDocument>> | null>(null);
  const projectOptions = useMemo(() => (projectsQuery.data ?? []).map((project) => ({
    value: String(project.id),
    label: project.code ? `${project.code} — ${project.title}` : project.title,
  })), [projectsQuery.data]);
  const topicOptions = useMemo(() => (topicsQuery.data ?? []).map((topic) => ({
    value: String(topic.id),
    label: topic.name,
  })), [topicsQuery.data]);
  const groupsQuery = useGroupsByTopic(
    visibility === 'GROUP_MEMBERS' ? managedLabId : null,
    visibility === 'GROUP_MEMBERS' && Number(topicId) > 0 ? Number(topicId) : null,
  );
  const groupOptions = useMemo(() => (groupsQuery.data ?? []).map((group) => ({
    value: String(group.id),
    label: group.name,
  })), [groupsQuery.data]);

  const changeVisibility = (nextVisibility: KnowledgeVisibility) => {
    setVisibility(nextVisibility);
    setDomain(nextVisibility === 'PROJECT_MEMBERS' || nextVisibility === 'GROUP_MEMBERS' ? 'RESEARCH' : 'LAB');
    setProjectId('');
    setTopicId('');
    setGroupId('');
    setError('');
  };

  const changeGroup = (nextGroupId: string) => {
    setGroupId(nextGroupId);
    setProjectId('');
    setError('');
  };

  const save = useMutation({
    mutationFn: (request: KnowledgeDocumentRequest) => documentId
      ? reindexKnowledgeDocument(Number(documentId), request)
      : ingestKnowledgeDocument(request),
    onSuccess: (result) => {
      setError('');
      setLastResult(result);
      setDocumentId(String(result.documentId));
      setVersion(String(result.version + 1));
    },
  });
  const revoke = useMutation({
    mutationFn: () => revokeKnowledgeDocument(Number(documentId)),
    onSuccess: () => { setLastResult(null); setDocumentId(''); },
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const needsProject = visibility === 'PROJECT_MEMBERS';
    const needsGroup = visibility === 'GROUP_MEMBERS';
    if (!resourceId.trim() || !title.trim() || !content.trim() || Number(version) <= 0) {
      setError('Vui lòng điền đầy đủ mã nguồn, phiên bản, tiêu đề và nội dung.'); return;
    }
    if (!/^[A-Z][A-Z0-9_]*$/.test(sourceType)) {
      setError('Loại nguồn phải viết hoa và chỉ gồm chữ, số hoặc dấu gạch dưới.'); return;
    }
    if (!isAdmin && !managedLabId) { setError('Tài khoản chưa được phân công quản lý PTN.'); return; }
    if (needsProject && Number(projectId) <= 0) { setError('Vui lòng chọn dự án được sử dụng tài liệu.'); return; }
    if (needsGroup && Number(groupId) <= 0) { setError('Vui lòng chọn nhóm được sử dụng tài liệu.'); return; }
    setError(''); setLastResult(null);
    save.mutate({
      domain, resourceId: resourceId.trim(), version: Number(version), sourceType,
      title: title.trim(), content: content.trim(), visibility,
      ...(!isAdmin ? { labId: managedLabId! } : {}),
      ...(needsProject ? { projectId: Number(projectId) } : {}),
      ...(needsGroup ? { groupId: Number(groupId) } : {}),
    });
  };

  const startNewDocument = () => {
    setDocumentId('');
    setResourceId('');
    setVersion('1');
    setSourceType('POLICY');
    setTitle('');
    setContent('');
    setProjectId('');
    setTopicId('');
    setGroupId('');
    setDomain(isAdmin ? 'ADMIN' : 'LAB');
    setVisibility(isAdmin ? 'ADMIN_ONLY' : 'LAB_MEMBERS');
    setError('');
    setLastResult(null);
    save.reset();
    revoke.reset();
  };

  return (
    <section className="mx-auto max-w-6xl">
      <header className="mb-6"><p className="flex items-center gap-2 text-sm font-semibold uppercase tracking-wide text-slate-500"><BookOpenCheck aria-hidden="true" className="h-4 w-4" /> Kho tri thức</p><h1 className="mt-1 text-2xl font-semibold text-slate-950 dark:text-white">Kho tri thức trợ lý</h1><p className="mt-2 max-w-3xl text-sm leading-6 text-slate-600 dark:text-slate-300">Thêm tài liệu hướng dẫn để trợ lý sử dụng đúng thông tin của bạn.</p></header>
      {!isAdmin && managedLabName ? <div className="mb-5 rounded-lg border border-blue-100 bg-blue-50 px-4 py-3 text-sm text-blue-900"><span className="font-semibold">PTN đang quản lý:</span> {managedLabName}</div> : null}
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_320px]">
        <form className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900" onSubmit={submit}>
          {documentId ? <div className="mb-4 rounded-md border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-900 dark:border-blue-900 dark:bg-blue-950/30 dark:text-blue-200"><p className="font-semibold">Đang cập nhật tài liệu #{documentId}</p><p className="mt-1">Giữ nguyên mã tài nguyên và phạm vi hiển thị. Phiên bản mới phải lớn hơn phiên bản đã lưu.</p></div> : null}
          <div className="grid gap-4 sm:grid-cols-2">
            {isAdmin ? <Select disabled label="Phạm vi tài liệu" value="ADMIN_ONLY" options={[{ value: 'ADMIN_ONLY', label: 'Chỉ quản trị viên' }]} onChange={() => undefined} /> : <Select disabled={Boolean(documentId)} label="Ai được sử dụng tài liệu?" value={visibility} options={managerScopeOptions} onChange={(value) => changeVisibility(value as KnowledgeVisibility)} />}
            <TextInput label="Mã tài nguyên" value={resourceId} onChange={setResourceId} placeholder="Ví dụ: quy-dinh-an-toan" />
            {visibility === 'PROJECT_MEMBERS' ? <Select disabled={Boolean(documentId) || projectsQuery.isLoading} label="Dự án được sử dụng" value={projectId} placeholder={projectsQuery.isLoading ? 'Đang tải dự án...' : 'Chọn dự án'} options={projectOptions} onChange={(value) => { setProjectId(value); setError(''); }} /> : null}
            {visibility === 'GROUP_MEMBERS' ? <Select disabled={Boolean(documentId) || topicsQuery.isLoading} label="Đề tài nghiên cứu khoa học" value={topicId} placeholder={topicsQuery.isLoading ? 'Đang tải đề tài...' : 'Chọn đề tài'} options={topicOptions} onChange={(value) => { setTopicId(value); setGroupId(''); setError(''); }} /> : null}
            {visibility === 'GROUP_MEMBERS' && topicId ? <Select disabled={Boolean(documentId) || groupsQuery.isLoading} label="Nhóm trong đề tài" value={groupId} placeholder={groupsQuery.isLoading ? 'Đang tải nhóm...' : 'Chọn nhóm'} options={groupOptions} onChange={changeGroup} /> : null}
          </div>
          {!isAdmin && (projectsQuery.isError || topicsQuery.isError || groupsQuery.isError) ? <p className="mt-3 text-sm text-red-700 dark:text-red-300" role="alert">Không thể tải danh sách đề tài, dự án hoặc nhóm. Vui lòng thử lại.</p> : null}
          {!isAdmin && visibility === 'PROJECT_MEMBERS' && !projectsQuery.isLoading && projectOptions.length === 0 ? <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">PTN chưa có dự án nghiên cứu đang hoạt động.</p> : null}
          {!isAdmin && visibility === 'GROUP_MEMBERS' && !topicsQuery.isLoading && topicOptions.length === 0 ? <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">PTN chưa có đề tài nghiên cứu khoa học.</p> : null}
          {!isAdmin && visibility === 'GROUP_MEMBERS' && topicId && !groupsQuery.isLoading && groupOptions.length === 0 ? <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">Đề tài này chưa có nhóm nghiên cứu.</p> : null}
          <details className="mt-4 rounded-md border border-slate-200 px-4 py-3 text-sm dark:border-slate-700">
            <summary className="cursor-pointer font-semibold text-slate-700 dark:text-slate-200">Thông tin nâng cao</summary>
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <TextInput label="Loại nguồn" value={sourceType} onChange={(value) => setSourceType(value.toUpperCase())} placeholder="POLICY" />
              <NumberInput label="Phiên bản" value={version} onChange={setVersion} />
              {!isAdmin ? <TextInput label="ID PTN" value={String(managedLabId ?? '')} onChange={() => undefined} disabled /> : null}
            </div>
          </details>
          <TextInput className="mt-4" label="Tiêu đề tài liệu" value={title} onChange={setTitle} />
          <label className="mt-4 block text-sm font-semibold text-slate-700 dark:text-slate-200" htmlFor="knowledge-content">Nội dung văn bản<textarea id="knowledge-content" className="mt-2 min-h-64 w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-base leading-6 text-slate-950 dark:border-slate-700 dark:bg-slate-950 dark:text-white" maxLength={512000} value={content} onChange={(event) => setContent(event.target.value)} /></label>
          {error || save.isError ? <p className="mt-4 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-200" role="alert">{error || getError(save.error)}</p> : null}
          <Button className="mt-5" loading={save.isPending} loadingText="Đang lập chỉ mục..." type="submit"><FileUp aria-hidden="true" className="h-4 w-4" /> {documentId ? 'Lập chỉ mục lại' : 'Nạp tài liệu'}</Button>
        </form>

        <aside className="space-y-5">
          <details className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900"><summary className="flex cursor-pointer list-none items-center gap-2 text-base font-semibold text-slate-950 dark:text-white"><RefreshCw aria-hidden="true" className="h-4 w-4" /> Quản lý tài liệu</summary><label className="mt-4 block text-sm font-medium text-slate-700 dark:text-slate-200" htmlFor="knowledge-document-id">Mã tài liệu<input id="knowledge-document-id" className="mt-2 min-h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-base dark:border-slate-700 dark:bg-slate-950 dark:text-white" min={1} type="number" value={documentId} onChange={(event) => setDocumentId(event.target.value)} /></label><p className="mt-2 text-xs leading-5 text-slate-500 dark:text-slate-400">Nhập mã tài liệu để lập chỉ mục lại hoặc thu hồi.</p><Button className="mt-4 w-full" disabled={Number(documentId) <= 0} loading={revoke.isPending} variant="danger" onClick={() => setRevokeConfirmationOpen(true)}><Trash2 aria-hidden="true" className="h-4 w-4" /> Thu hồi tài liệu</Button>{revoke.isError ? <p className="mt-3 text-sm text-red-700 dark:text-red-300" role="alert">{getError(revoke.error)}</p> : null}</details>
          {lastResult ? <section className="rounded-lg border border-emerald-200 bg-emerald-50 p-5 text-sm text-emerald-900 dark:border-emerald-900 dark:bg-emerald-950/30 dark:text-emerald-200"><h2 className="font-semibold">Đã lập chỉ mục thành công</h2><dl className="mt-3 grid grid-cols-2 gap-2"><dt>Mã tài liệu</dt><dd className="font-semibold text-right">{lastResult.documentId}</dd><dt>Không gian lưu trữ</dt><dd className="font-semibold text-right">{lastResult.namespace}</dd><dt>Số đoạn</dt><dd className="font-semibold text-right">{lastResult.chunkCount}</dd><dt>Phiên bản</dt><dd className="font-semibold text-right">{lastResult.version}</dd></dl><Button className="mt-4 w-full" type="button" variant="outline" onClick={startNewDocument}>Nạp tài liệu mới</Button></section> : null}
          <section className="rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200"><p className="flex gap-2 font-semibold"><ShieldAlert aria-hidden="true" className="h-5 w-5 shrink-0" /> Tài liệu không phải chỉ dẫn hệ thống</p><p className="mt-2 leading-6">Nội dung trong tài liệu không thể ghi đè quy định, quyền người dùng hoặc quy tắc an toàn của trợ lý.</p></section>
        </aside>
      </div>
      <ConfirmDialog
        confirmLabel="Thu hồi"
        isOpen={revokeConfirmationOpen}
        isPending={revoke.isPending}
        message="Tài liệu và toàn bộ đoạn nội dung liên quan sẽ bị thu hồi quyền truy cập."
        onCancel={() => setRevokeConfirmationOpen(false)}
        onConfirm={() => revoke.mutate(undefined, { onSuccess: () => setRevokeConfirmationOpen(false) })}
        title="Thu hồi tài liệu?"
      />
    </section>
  );
}

function TextInput({ className = '', disabled = false, label, onChange, placeholder, value }: { className?: string; disabled?: boolean; label: string; onChange: (value: string) => void; placeholder?: string; value: string }) { const id = `knowledge-${label.toLowerCase().replace(/\s+/g, '-')}`; return <label className={`block text-sm font-semibold text-slate-700 dark:text-slate-200 ${className}`} htmlFor={id}>{label}<input id={id} className="mt-2 min-h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-base text-slate-950 disabled:bg-slate-100 dark:border-slate-700 dark:bg-slate-950 dark:text-white dark:disabled:bg-slate-800" disabled={disabled} placeholder={placeholder} value={value} onChange={(event) => onChange(event.target.value)} /></label>; }
function NumberInput({ label, onChange, value }: { label: string; onChange: (value: string) => void; value: string }) { return <TextInput label={label} value={value} onChange={onChange} />; }
function Select({ disabled = false, label, onChange, options, placeholder, value }: { disabled?: boolean; label: string; onChange: (value: string) => void; options: Array<{ value: string; label: string }>; placeholder?: string; value: string }) { const id = `knowledge-${label.toLowerCase().replace(/\s+/g, '-')}`; return <label className="block text-sm font-semibold text-slate-700 dark:text-slate-200" htmlFor={id}>{label}<select id={id} className="mt-2 min-h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-base text-slate-950 disabled:bg-slate-100 disabled:text-slate-500 dark:border-slate-700 dark:bg-slate-950 dark:text-white dark:disabled:bg-slate-800" disabled={disabled} value={value} onChange={(event) => onChange(event.target.value)}>{placeholder ? <option value="">{placeholder}</option> : null}{options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>; }
