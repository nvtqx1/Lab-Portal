import axios from 'axios';
import { Bot, BookOpen, CalendarClock, Check, MessageSquare, Plus, Send, ShieldCheck, Sparkles, UserRound, X } from 'lucide-react';
import { FormEvent, useEffect, useRef, useState } from 'react';

import { Button } from '../../../shared/components';
import type { Response } from '../../../shared/types';
import {
  useAssistantConversation,
  useAssistantConversations,
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
        {response.assistantKey ? <span className="text-slate-500 dark:text-slate-400">{response.assistantKey}</span> : null}
      </div>
      <p className="whitespace-pre-wrap text-sm leading-7 text-slate-700 dark:text-slate-200">{response.answer}</p>
      {response.actionPreview ? (
        <div className="mt-4 rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-100">
          <p className="flex items-center gap-2 font-semibold"><CalendarClock aria-hidden="true" className="h-4 w-4" /> Xem trước ca Lab</p>
          <dl className="mt-3 grid gap-2 sm:grid-cols-2">
            <div><dt className="text-xs opacity-70">Lab</dt><dd>#{response.actionPreview.labId}</dd></div>
            <div><dt className="text-xs opacity-70">Sức chứa</dt><dd>{response.actionPreview.capacity} người</dd></div>
            <div><dt className="text-xs opacity-70">Bắt đầu</dt><dd>{new Date(response.actionPreview.startTime).toLocaleString('vi-VN')}</dd></div>
            <div><dt className="text-xs opacity-70">Kết thúc</dt><dd>{new Date(response.actionPreview.endTime).toLocaleString('vi-VN')}</dd></div>
          </dl>
          <p className="mt-3 text-xs">Chưa có dữ liệu nào được ghi. Backend sẽ kiểm tra lại quyền và trạng thái khi bạn xác nhận.</p>
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
      <div className="mt-3 flex flex-wrap gap-2 text-xs text-slate-500 dark:text-slate-400">
        <span className="rounded-full bg-slate-100 px-2 py-1 dark:bg-slate-800">Prompt: {response.promptTokens} tokens</span>
        <span className="rounded-full bg-slate-100 px-2 py-1 dark:bg-slate-800">Completion: {response.completionTokens} tokens</span>
      </div>
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
  const conversationEndRef = useRef<HTMLDivElement>(null);
  const chatMutation = useUnifiedAssistantChat();
  const actionMutation = useResolveAssistantAction();
  const conversations = useAssistantConversations();
  const conversation = useAssistantConversation(conversationId);

  useEffect(() => {
    if (conversation.data) {
      setTurns(restoredTurns(conversation.data));
    }
  }, [conversation.data]);

  useEffect(() => {
    conversationEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [turns]);

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
    setTurns([]);
    setInput('');
    setValidationError('');
  };

  const openConversation = (selectedConversationId: number) => {
    if (chatMutation.isPending || actionMutation.isPending) return;
    if (selectedConversationId === conversationId) return;
    setTurns([]);
    setConversationId(selectedConversationId);
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
                  ? `Đã tạo ca Lab thành công (mã ca #${actionResult.targetId}).`
                  : 'Đã hủy bản xem trước. Không có dữ liệu nào được ghi.',
                actionPreview: null,
                actionResult,
              },
            }
          : turn
      ))),
      onError: (error) => setTurns((current) => current.map((turn) => (
        turn.id === turnId ? { ...turn, actionError: getErrorMessage(error) } : turn
      ))),
    });
  };

  return (
    <section className="mx-auto max-w-5xl">
      <header className="mb-6">
        <p className="flex items-center gap-2 text-sm font-semibold uppercase tracking-wide text-slate-500"><Bot aria-hidden="true" className="h-4 w-4" /> Smart Research Lab</p>
        <h1 className="mt-1 text-2xl font-semibold text-slate-950 dark:text-white">Hỏi đáp với trợ lý AI</h1>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-slate-600 dark:text-slate-300">Chỉ cần nhập câu hỏi. Spring tự xác định nghiệp vụ, dữ liệu và quyền được phép trước khi gọi model.</p>
      </header>

      <div className="grid min-h-[680px] overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900 md:grid-cols-[240px_minmax(0,1fr)]">
        <aside className="border-b border-slate-200 bg-slate-50 p-3 dark:border-slate-800 dark:bg-slate-950 md:border-b-0 md:border-r">
          <Button disabled={chatMutation.isPending || actionMutation.isPending} className="w-full" onClick={startNewConversation} type="button" variant="outline">
            <Plus aria-hidden="true" className="h-4 w-4" /> Cuộc trò chuyện mới
          </Button>
          <div className="mt-3 max-h-40 space-y-1 overflow-y-auto md:max-h-[600px]">
            {conversations.data?.map((item) => (
              <button
                className={`flex w-full items-start gap-2 rounded-md px-3 py-2 text-left text-sm transition ${
                  conversationId === item.id
                    ? 'bg-slate-200 text-slate-950 dark:bg-slate-800 dark:text-white'
                    : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-900'
                }`}
                key={item.id}
                disabled={chatMutation.isPending || actionMutation.isPending}
                onClick={() => openConversation(item.id)}
                type="button"
              >
                <MessageSquare aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0" />
                <span className="line-clamp-2">{item.title}</span>
              </button>
            ))}
            {conversations.isLoading ? (
              <p className="px-3 py-2 text-xs text-slate-500">Đang tải lịch sử…</p>
            ) : null}
          </div>
        </aside>

        <div className="flex min-w-0 flex-col">
          <div className="flex items-center gap-2 border-b border-slate-200 bg-slate-50 px-4 py-3 text-xs text-slate-600 dark:border-slate-800 dark:bg-slate-950 dark:text-slate-300 sm:px-6">
            <ShieldCheck aria-hidden="true" className="h-4 w-4 shrink-0" />
            Không cần chọn chủ đề hoặc tài nguyên; backend kiểm tra lại quyền cho từng yêu cầu.
          </div>

          <div aria-live="polite" className="flex-1 space-y-5 overflow-y-auto p-4 sm:p-6">
          {turns.length === 0 ? (
            <div className="flex min-h-96 flex-col items-center justify-center text-center">
              <Sparkles aria-hidden="true" className="h-9 w-9 text-slate-400" />
              <h2 className="mt-4 text-lg font-semibold text-slate-950 dark:text-white">Bạn muốn biết điều gì?</h2>
              <p className="mt-2 max-w-lg text-sm leading-6 text-slate-600 dark:text-slate-300">Hỏi bằng ngôn ngữ tự nhiên về hệ thống, phòng thí nghiệm, booking hoặc nghiên cứu trong phạm vi quyền của bạn.</p>
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
                  <p className="text-sm text-slate-500 dark:text-slate-400" role="status">Đang xác định nghiệp vụ và dựng context được cấp quyền…</p>
                )}
              </div>
            </article>
          ))}
          <div ref={conversationEndRef} />
          </div>

          <form className="border-t border-slate-200 p-4 dark:border-slate-800 sm:p-5" onSubmit={handleSubmit}>
          <label className="sr-only" htmlFor="assistant-input">Câu hỏi</label>
          <textarea
            id="assistant-input"
            className="min-h-24 w-full resize-y rounded-lg border border-slate-300 bg-white px-3 py-3 text-base text-slate-950 outline-none focus-visible:ring-2 focus-visible:ring-slate-500 disabled:opacity-60 dark:border-slate-700 dark:bg-slate-950 dark:text-white"
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
    </section>
  );
}
