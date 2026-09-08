export type OperationalLogKind = 'ai-usage' | 'ai-actions' | 'face-checkins';

export interface OperationalLogPage<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface OperationalFilters {
  userId?: number;
  module?: string;
  labId?: number;
  assistantKey?: string;
  resourceType?: string;
  resourceId?: number;
  bookingId?: number;
  result?: string;
  from?: string;
  to?: string;
}

export interface AiUsageLog {
  id: number; userId: number; assistantKey: string; module: string; labId: number | null;
  projectId: number | null; groupId: number | null; promptTokens: number; completionTokens: number;
  status: string; errorRecorded: boolean; createdAt: string;
}

export interface AiActionLog {
  id: number; requestedById: number; assistantKey: string; actionType: string; resourceType: string;
  resourceId: number; status: string; executionStatus: string; createdAt: string;
}

export interface FaceCheckinLog {
  id: number; bookingId: number; userId: number; studentName: string | null; labId: number; method: string; result: string;
  failureReason: string | null; createdAt: string;
}

export type OperationalLogItem = AiUsageLog | AiActionLog | FaceCheckinLog;
