import axios from 'axios';
import { Bot, BookOpen, CalendarClock, Check, LoaderCircle, MessageSquare, Plus, Send, ShieldCheck, Sparkles, Trash2, UserRound, X } from 'lucide-react';
import { FormEvent, useEffect, useLayoutEffect, useRef, useState } from 'react';

import { Button, ConfirmDialog, toast } from '../../../shared/components';
import type { Response } from '../../../shared/types';
import {
  useAssistantConversation,
  useAssistantConversations,
  useDeleteAssistantConversation,
  useResolveAssistantAction,
  useUnifiedAssistantChat,
} from '../hooks';
import type { AssistantConversationDetail, UnifiedChatResponse } from '../types';

interface ChatTurn {
  id: string;
  question: string;
  response?: UnifiedChatResponse;
  error?: string;
  actionError?: string;
}

function newTurnId() {
  return typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}`;
}

function restoredTurns(conversation: AssistantConversationDetail): ChatTurn[] {
  const restored: ChatTurn[] = [];
  conversation.messages.forEach((message) => {
    if (message.role === 'USER') {
      restored.push({ id: `message-${message.id}`, question: message.content });
      return;
    }
    if (message.role === 'ASSISTANT' && restored.length > 0) {
      restored[restored.length - 1].response = message.response ?? {
        conversationId: conversation.id,
        type: 'ANSWER',
        assistantKey: null,
        answer: message.content,
        promptTokens: 0,
        completionTokens: 0,
        citations: [],
        actionPreview: null,
        actionResult: null,
      };
    }
  });
  return restored;
}

function getErrorMessage(error: unknown) {
  if (axios.isAxiosError(error)) {
    const body = error.response?.data as Partial<Response<unknown>> | undefined;
    return body?.message ?? 'Trợ lý chưa sẵn sàng. Vui lòng kiểm tra model hoặc thử lại sau.';
  }
  return 'Không thể kết nối với trợ lý AI.';
}

function responseLabel(response: UnifiedChatResponse) {
  if (response.type === 'CLARIFICATION_REQUIRED') return 'Cần thêm thông tin';
  if (response.type === 'REFUSED') return 'Yêu cầu bị từ chối';
  if (response.type === 'ACTION_PREVIEW') return 'Bản xem trước';
  if (response.type === 'ACTION_RESULT') return 'Kết quả thao tác';
  return 'Câu trả lời';
}

function assistantDisplayName(key: string) {
  if (key === 'ADMIN_ASSISTANT') return 'Trợ lý quản trị';
  if (key === 'LAB_ASSISTANT') return 'Trợ lý PTN';
  if (key === 'RESEARCH_ASSISTANT') return 'Trợ lý nghiên cứu';
  return 'Trợ lý AI';
}

function AssistantAnswer({ response, actionError, actionPending, onResolve }: {
  response: UnifiedChatResponse;
  actionError?: string;
  actionPending: boolean;
  onResolve: (suggestionId: number, decision: 'confirm' | 'cancel') => void;
}) {
  return (
    <div className="min-w-0 flex-1">
      <div className="mb-2 flex flex-wrap items-center gap-2 text-xs">
        <span className="rounded-full bg-slate-200 px-2 py-1 font-semibold text-slate-700 dark:bg-slate-800 dark:text-slate-200">
          {responseLabel(response)}
        </span>
        {response.assistantKey ? <span className="text-slate-500 dark:text-slate-400">{assistantDisplayName(response.assistantKey)}</span> : null}
      </div>
      <p className="whitespace-pre-wrap text-sm leading-7 text-slate-700 dark:text-slate-200">{response.answer}</p>
      {response.actionPreview ? (
        <div className="mt-4 rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950 shadow-sm dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-100">
          <p className="flex items-center gap-2 font-semibold"><CalendarClock aria-hidden="true" className="h-4 w-4" /> Xem trước ca PTN</p>
          <dl className="mt-3 grid gap-2 sm:grid-cols-2">
            <div className="rounded-lg bg-white/70 p-3 dark:bg-slate-950/30"><dt className="text-xs opacity-70">PTN</dt><dd className="mt-1 font-medium">{response.actionPreview.labName ?? `PTN #${response.actionPreview.labId}`}</dd></div>
            <div className="rounded-lg bg-white/70 p-3 dark:bg-slate-950/30"><dt className="text-xs opacity-70">Sức chứa</dt><dd className="mt-1 font-medium">{response.actionPreview.capacity} người</dd></div>
            <div className="rounded-lg bg-white/70 p-3 dark:bg-slate-950/30"><dt className="text-xs opacity-70">Bắt đầu</dt><dd className="mt-1 font-medium">{new Date(response.actionPreview.startTime).toLocaleString('vi-VN')}</dd></div>
            <div className="rounded-lg bg-white/70 p-3 dark:bg-slate-950/30"><dt className="text-xs opacity-70">Kết thúc</dt><dd className="mt-1 font-medium">{new Date(response.actionPreview.endTime).toLocaleString('vi-VN')}</dd></div>
          </dl>
          <p className="mt-3 text-xs">Chưa có dữ liệu nào được ghi. Hệ thống sẽ kiểm tra quyền và trạng thái khi bạn xác nhận.</p>
          <div className="mt-4 flex flex-wrap gap-2">
            <Button disabled={actionPending} onClick={() => onResolve(response.actionPreview!.suggestionId, 'confirm')} type="button">
              <Check aria-hidden="true" className="h-4 w-4" /> Xác nhận tạo ca
            </Button>
            <Button disabled={actionPending} onClick={() => onResolve(response.actionPreview!.suggestionId, 'cancel')} type="button" variant="secondary">
              <X aria-hidden="true" className="h-4 w-4" /> Hủy
            </Button>
          </div>
          {actionError ? <p className="mt-3 text-sm font-medium text-red-700 dark:text-red-300" role="alert">{actionError}</p> : null}
        </div>
      ) : null}
      {!response.actionPreview && actionError ? (
        <p className="mt-3 text-sm font-medium text-red-700 dark:text-red-300" role="alert">{actionError}</p>
      ) : null}
      <details className="mt-3 text-xs text-slate-500 dark:text-slate-400">
        <summary className="min-h-11 w-fit cursor-pointer rounded-lg py-3 font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slate-500">
          Chi tiết kỹ thuật
        </summary>
        <div className="flex flex-wrap gap-2 pb-1">
          <span className="rounded-full bg-slate-100 px-2 py-1 dark:bg-slate-800">Prompt: {response.promptTokens} tokens</span>
          <span className="rounded-full bg-slate-100 px-2 py-1 dark:bg-slate-800">Completion: {response.completionTokens} tokens</span>
        </div>
      </details>
      {response.citations.length ? (
        <details className="mt-4 border-t border-slate-200 pt-3 dark:border-slate-800">
          <summary className="flex cursor-pointer list-none items-center gap-2 text-sm font-semibold text-slate-800 dark:text-slate-100">
            <BookOpen aria-hidden="true" className="h-4 w-4" />
            {response.citations.length} nguồn RAG được cấp quyền
          </summary>
          <ul className="mt-3 grid gap-2 sm:grid-cols-2">
            {response.citations.map((citation) => (
              <li className="rounded-md border border-slate-200 bg-white p-3 text-xs text-slate-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300" key={`${citation.documentId}-${citation.chunkIndex}`}>
                <p className="font-semibold text-slate-900 dark:text-white">{citation.sourceType} · {citation.resourceId}</p>
                <p className="mt-1">Phiên bản {citation.version}, đoạn {citation.chunkIndex + 1}{citation.pageNumber ? `, trang ${citation.pageNumber}` : ''}</p>
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </div>
  );
}

export function AssistantPage() {
  const [input, setInput] = useState('');
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [conversationId, setConversationId] = useState<number | null>(null);
  const [validationError, setValidationError] = useState('');
  const [conversationPendingDelete, setConversationPendingDelete] = useState<number | null>(null);
  const conversationEndRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const olderScroll = useRef<{ height: number; top: number } | null>(null);
  const loadedPageCount = useRef(0);
  const chatMutation = useUnifiedAssistantChat();
  const actionMutation = useResolveAssistantAction();
  const deleteConversationMutation = useDeleteAssistantConversation();
  const conversations = useAssistantConversations();
  const conversation = useAssistantConversation(conversationId);

  useEffect(() => {
    if (conversation.data) {
      setTurns(restoredTurns(conversation.data));
    }
  }, [conversation.data]);

  useLayoutEffect(() => {
    const viewport = scrollRef.current;
    if (olderScroll.current && viewport) {
      if (conversation.data && conversation.data.messages.length > loadedPageCount.current) {
        viewport.scrollTop = olderScroll.current.top + viewport.scrollHeight - olderScroll.current.height;
        olderScroll.current = null;
      }
      return;
    }
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    conversationEndRef.current?.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'nearest' });
  }, [turns]);

  const loadOlderMessages = async () => {
    const viewport = scrollRef.current;
    if (!viewport || conversation.isFetching || chatMutation.isPending || actionMutation.isPending) return;
    loadedPageCount.current = conversation.data?.messages.length ?? 0;
    olderScroll.current = { height: viewport.scrollHeight, top: viewport.scrollTop };
    const result = await conversation.fetchNextPage();
    if (result.isError) olderScroll.current = null;
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const question = input.trim();
    if (!question || chatMutation.isPending || actionMutation.isPending) {
      if (!question) setValidationError('Vui lòng nhập câu hỏi cho trợ lý.');
      return;
    }

    const turnId = newTurnId();
    setInput('');
    setValidationError('');
    setTurns((current) => [...current, { id: turnId, question }]);
    chatMutation.mutate({ input: question, ...(conversationId === null ? {} : { conversationId }) }, {
      onSuccess: (response) => {
        setConversationId(response.conversationId);
        setTurns((current) => current.map((turn) => (
          turn.id === turnId ? { ...turn, response } : turn
        )));
      },
      onError: (error) => setTurns((current) => current.map((turn) => (
        turn.id === turnId ? { ...turn, error: getErrorMessage(error) } : turn
      ))),
    });
  };

  const startNewConversation = () => {
    if (chatMutation.isPending || actionMutation.isPending) return;
    setConversationId(null);
    olderScroll.current = null;
    setTurns([]);
    setInput('');
    setValidationError('');
  };

  const openConversation = (selectedConversationId: number) => {
    if (chatMutation.isPending || actionMutation.isPending || deleteConversationMutation.isPending) return;
    if (selectedConversationId === conversationId) return;
    setTurns([]);
    setConversationId(selectedConversationId);
    olderScroll.current = null;
  };

  const handleDeleteConversation = (selectedConversationId: number) => {
    if (chatMutation.isPending || actionMutation.isPending || deleteConversationMutation.isPending) return;
    setConversationPendingDelete(selectedConversationId);
  };

  const confirmDeleteConversation = () => {
    if (conversationPendingDelete === null || deleteConversationMutation.isPending) return;
    const selectedConversationId = conversationPendingDelete;
    deleteConversationMutation.mutate(selectedConversationId, {
      onSuccess: () => {
        setConversationPendingDelete(null);
        if (selectedConversationId === conversationId) {
          setConversationId(null);
          setTurns([]);
          olderScroll.current = null;
        }
        toast.success('Đã xóa lịch sử cuộc trò chuyện.');
      },
      onError: (error) => toast.error(getErrorMessage(error)),
    });
  };

  const handleResolveAction = (turnId: string, suggestionId: number, decision: 'confirm' | 'cancel') => {
    if (chatMutation.isPending || actionMutation.isPending) return;
    actionMutation.mutate({ suggestionId, decision }, {
      onSuccess: (actionResult) => setTurns((current) => current.map((turn) => (
        turn.id === turnId && turn.response
          ? {
              ...turn,
              actionError: undefined,
              response: {
                ...turn.response,
                type: 'ACTION_RESULT',
                answer: actionResult.status === 'EXECUTED'
                  ? `Đã tạo ca PTN thành công (mã ca #${actionResult.targetId}).`
                  : 'Đã hủy bản xem trước. Không có dữ liệu nào được ghi.',
                actionPreview: null,
                actionResult,
              },
            }
          : turn
      ))),
      onError: (error) => setTurns((current) => current.map((turn) => {
        if (turn.id !== turnId) return turn;
        const message = getErrorMessage(error);
        return {
          ...turn,
          actionError: message,
          response: turn.response ? {
            ...turn.response,
            type: 'REFUSED',
            answer: 'Bản xem trước không còn hợp lệ và đã được đóng. Vui lòng tạo lại với thời gian khác.',
            actionPreview: null,
            actionResult: null,
          } : turn.response,
        };
      })),
    });
  };

  return (
    <section className="mx-auto max-w-6xl">
      <header className="mb-6">
        <p className="flex items-center gap-2 text-sm font-semibold uppercase tracking-wide text-slate-500"><Bot aria-hidden="true" className="h-4 w-4" /> Trợ lý PTN</p>
        <h1 className="mt-1 text-2xl font-semibold text-slate-950 dark:text-white">Hỏi đáp với trợ lý AI</h1>
      </header>

      <div className="grid min-h-[680px] overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900 md:grid-cols-[260px_minmax(0,1fr)]">
        <aside className="border-b border-slate-200 bg-slate-50 p-3 dark:border-slate-800 dark:bg-slate-950 md:border-b-0 md:border-r">
          <Button disabled={chatMutation.isPending || actionMutation.isPending || deleteConversationMutation.isPending} className="w-full" onClick={startNewConversation} type="button" variant="outline">
            <Plus aria-hidden="true" className="h-4 w-4" /> Cuộc trò chuyện mới
          </Button>
          <div className="mt-3 max-h-40 space-y-1 overflow-y-auto md:max-h-[600px]">
            {conversations.data?.map((item) => (
              <div
                className={`flex min-h-11 w-full items-start gap-1 rounded-lg pr-1 text-left text-sm transition-colors duration-200 ${
                  conversationId === item.id
                    ? 'bg-slate-200 text-slate-950 dark:bg-slate-800 dark:text-white'
                    : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-900'
                }`}
                key={item.id}
              >
                <button
                  aria-current={conversationId === item.id ? 'page' : undefined}
                  className="flex min-h-11 min-w-0 flex-1 items-start gap-2 rounded-lg px-3 py-2.5 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slate-500"
                  disabled={chatMutation.isPending || actionMutation.isPending || deleteConversationMutation.isPending}
                  onClick={() => openConversation(item.id)}
                  type="button"
                >
                  <MessageSquare aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0" />
                  <span className="line-clamp-2">{item.title}</span>
                </button>
                <span className="ml-auto flex shrink-0 gap-1">
                  <button
                    aria-label={`Xóa cuộc trò chuyện: ${item.title}`}
                    className="flex h-11 w-11 items-center justify-center rounded-lg text-slate-400 transition-colors duration-200 hover:bg-red-100 hover:text-red-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-500"
                    disabled={chatMutation.isPending || actionMutation.isPending || deleteConversationMutation.isPending}
                    onClick={() => handleDeleteConversation(item.id)}
                    type="button"
                  >
                    <Trash2 aria-hidden="true" className="h-4 w-4" />
                  </button>
                </span>
              </div>
            ))}
            {conversations.isLoading ? (
              <p className="flex min-h-11 items-center gap-2 px-3 py-2 text-xs text-slate-500" role="status"><LoaderCircle aria-hidden="true" className="h-4 w-4 animate-spin" /> Đang tải lịch sử…</p>
            ) : null}
          </div>
        </aside>

        <div className="flex min-w-0 flex-col">
          <div ref={scrollRef} aria-live="polite" className="max-h-[65vh] flex-1 space-y-5 overflow-y-auto bg-slate-50/40 p-4 dark:bg-slate-950/20 sm:p-6">
          {conversation.hasNextPage ? (
            <div className="flex justify-center">
              <Button
                disabled={conversation.isFetching || chatMutation.isPending || actionMutation.isPending}
                loading={conversation.isFetchingNextPage}
                loadingText="Đang tải…"
                onClick={() => { void loadOlderMessages(); }}
                type="button"
                variant="outline"
              >
                Tải tin nhắn cũ hơn
              </Button>
            </div>
          ) : null}
          {turns.length === 0 ? (
            <div className="flex min-h-96 flex-col items-center justify-center text-center">
              <Sparkles aria-hidden="true" className="h-9 w-9 text-slate-400" />
              <h2 className="mt-4 text-lg font-semibold text-slate-950 dark:text-white">Bạn muốn biết điều gì?</h2>
              <p className="mt-2 max-w-lg text-sm leading-6 text-slate-600 dark:text-slate-300">Hỏi bằng ngôn ngữ tự nhiên về hệ thống, phòng thí nghiệm, lượt đặt hoặc nghiên cứu.</p>
            </div>
          ) : turns.map((turn) => (
            <article className="space-y-3" key={turn.id}>
              <div className="ml-auto max-w-[85%] rounded-2xl rounded-br-md bg-slate-900 px-4 py-3 text-white dark:bg-slate-100 dark:text-slate-950">
                <div className="mb-1 flex items-center gap-2 text-xs font-semibold opacity-70"><UserRound aria-hidden="true" className="h-3.5 w-3.5" /> Bạn</div>
                <p className="whitespace-pre-wrap text-sm leading-6">{turn.question}</p>
              </div>
              <div className="mr-auto flex max-w-[94%] gap-3 rounded-2xl rounded-bl-md border border-slate-200 bg-slate-50 p-4 dark:border-slate-700 dark:bg-slate-950">
                <div className="mt-0.5 h-fit rounded-full bg-white p-2 shadow-sm dark:bg-slate-900"><Bot aria-hidden="true" className="h-4 w-4" /></div>
                {turn.response ? <AssistantAnswer
                  actionError={turn.actionError}
                  actionPending={actionMutation.isPending || chatMutation.isPending}
                  response={turn.response}
                  onResolve={(suggestionId, decision) => handleResolveAction(turn.id, suggestionId, decision)}
                /> : turn.error ? (
                  <p className="text-sm leading-6 text-red-700 dark:text-red-300" role="alert">{turn.error}</p>
                ) : (
                  <p className="flex items-center gap-2 text-sm text-slate-500 dark:text-slate-400" role="status"><LoaderCircle aria-hidden="true" className="h-4 w-4 animate-spin" /> Đang xác định nghiệp vụ và dựng context được cấp quyền…</p>
                )}
              </div>
            </article>
          ))}
          <div ref={conversationEndRef} />
          </div>

          <form className="border-t border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900 sm:p-5" onSubmit={handleSubmit}>
          <label className="sr-only" htmlFor="assistant-input">Câu hỏi</label>
          <textarea
            id="assistant-input"
            className="min-h-24 w-full resize-y rounded-xl border border-slate-300 bg-white px-4 py-3 text-base text-slate-950 shadow-sm outline-none transition-colors duration-200 focus:border-slate-500 focus-visible:ring-2 focus-visible:ring-slate-500 disabled:opacity-60 dark:border-slate-700 dark:bg-slate-950 dark:text-white"
            disabled={chatMutation.isPending || actionMutation.isPending}
            maxLength={32768}
            placeholder="Nhập câu hỏi… (Enter để gửi, Shift + Enter để xuống dòng)"
            value={input}
            onChange={(event) => { setInput(event.target.value); setValidationError(''); }}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                event.currentTarget.form?.requestSubmit();
              }
            }}
          />
          {validationError ? <p className="mt-2 text-sm font-medium text-red-700 dark:text-red-300" role="alert">{validationError}</p> : null}
          <div className="mt-3 flex items-center justify-between gap-3">
            <p className="flex items-start gap-2 text-xs leading-5 text-slate-500 dark:text-slate-400"><ShieldCheck aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0" /> AI không nhận quyền truy cập DB trực tiếp và không thể tự mở rộng phạm vi dữ liệu.</p>
            <Button loading={chatMutation.isPending} loadingText="Đang trả lời…" type="submit"><Send aria-hidden="true" className="h-4 w-4" /> Gửi</Button>
          </div>
          </form>
        </div>
      </div>
      <ConfirmDialog
        cancelLabel="Không xóa"
        confirmLabel="Xóa lịch sử"
        isOpen={conversationPendingDelete !== null}
        isPending={deleteConversationMutation.isPending}
        message="Cuộc trò chuyện sẽ bị xóa khỏi lịch sử của bạn."
        onCancel={() => setConversationPendingDelete(null)}
        onConfirm={confirmDeleteConversation}
        title="Xóa cuộc trò chuyện?"
      />
    </section>
  );
}
