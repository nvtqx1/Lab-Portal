export type AssistantKey = 'ADMIN_ASSISTANT' | 'LAB_ASSISTANT' | 'RESEARCH_ASSISTANT';

export type AssistantCapability =
  | 'ADMIN_SYSTEM_SUMMARY'
  | 'ADMIN_AUDIT_SUMMARY'
  | 'ADMIN_USER_STATUS_LOOKUP'
  | 'ADMIN_CONFIG_DRAFT'
  | 'ADMIN_ACCOUNT_ACTION_DRAFT'
  | 'LAB_POLICY_READ'
  | 'LAB_SLOT_READ'
  | 'LAB_OWN_BOOKING_READ'
  | 'LAB_MANAGED_SUMMARY'
  | 'LAB_BOOKING_DRAFT'
  | 'LAB_CHECKIN_GUIDANCE'
  | 'RESEARCH_PROJECT_SUMMARY'
  | 'RESEARCH_GROUP_SUMMARY'
  | 'RESEARCH_ASSIGNED_TASK_READ'
  | 'RESEARCH_TASK_PROPOSAL_DRAFT'
  | 'RESEARCH_TASK_SUGGESTION_DRAFT'
  | 'RESEARCH_REPORT_REVIEW_DRAFT';

export interface AssistantChatRequest {
  input: string;
  capability: AssistantCapability;
  resourceId?: number;
  parentResourceId?: number;
}

export interface AssistantCitation {
  documentId: number;
  resourceId: string;
  version: number;
  chunkIndex: number;
  pageNumber: number | null;
  sourceType: string;
}

export interface AssistantChatResponse {
  assistantKey: AssistantKey;
  answer: string;
  promptTokens: number;
  completionTokens: number;
  citations: AssistantCitation[];
}

export type UnifiedChatResponseType =
  | 'ANSWER'
  | 'CLARIFICATION_REQUIRED'
  | 'REFUSED'
  | 'ACTION_PREVIEW'
  | 'ACTION_RESULT';

export interface UnifiedChatRequest {
  input: string;
  conversationId?: number;
}

export interface UnifiedChatResponse {
  conversationId: number;
  type: UnifiedChatResponseType;
  assistantKey: AssistantKey | null;
  answer: string;
  promptTokens: number;
  completionTokens: number;
  citations: AssistantCitation[];
  actionPreview: AssistantActionPreview | null;
  actionResult: AssistantActionResult | null;
}

export interface AssistantConversationSummary {
  id: number;
  title: string;
  updatedAt: string;
}

export interface AssistantConversationMessage {
  id: number;
  role: 'USER' | 'ASSISTANT' | 'SYSTEM';
  content: string;
  response: UnifiedChatResponse | null;
  createdAt: string;
}

export interface AssistantConversationDetail {
  id: number;
  title: string;
  messages: AssistantConversationMessage[];
}

export interface AssistantActionPreview {
  suggestionId: number;
  actionType: 'CREATE_LAB_SHIFT';
  status: 'AWAITING_CONFIRMATION';
  labId: number;
  startTime: string;
  endTime: string;
  capacity: number;
}

export interface AssistantActionResult {
  suggestionId: number;
  actionType: 'CREATE_LAB_SHIFT';
  status: 'EXECUTED' | 'CANCELLED';
  targetId: number | null;
}
