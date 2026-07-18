import { request } from './http';

export type MemoryType =
  | 'GAP_SOLUTION'
  | 'BUSINESS_TERM'
  | 'METRIC_DEFINITION'
  | 'OPERATION_PREFERENCE';

export type MemoryStatus = 'ACTIVE' | 'SUPERSEDED' | 'EXPIRED' | 'CONFLICT';
export type MemoryIndexStatus = 'PENDING' | 'INDEXED' | 'FAILED';
export type MemorySourceType = 'GAP_RECORD' | 'USER_INPUT' | 'BUSINESS_DOC' | 'SYSTEM';

export interface MemoryEntry {
  id: number;
  memoryNo: string;
  memoryKey: string;
  version: number;
  previousId: number | null;
  type: MemoryType;
  title: string;
  content: string;
  contentHash: string;
  sourceType: MemorySourceType;
  sourceRef: string;
  status: MemoryStatus;
  validFrom: string;
  validTo: string | null;
  indexStatus: MemoryIndexStatus;
  indexedCollection: string | null;
  embeddingModel: string | null;
  embeddingDimension: number | null;
  retryCount: number;
  nextRetryAt: string | null;
  lastIndexError: string | null;
  createdBy: string;
  createdAt: string;
  updatedBy: string;
  updatedAt: string;
}

export interface MemoryPage {
  items: MemoryEntry[];
  total: number;
  page: number;
  size: number;
}

export interface MemorySearchFilters {
  type?: MemoryType;
  status?: MemoryStatus;
  indexStatus?: MemoryIndexStatus;
  page: number;
  size: number;
}

export interface MemoryForm {
  type: MemoryType;
  title: string;
  content: string;
  sourceType: MemorySourceType;
  sourceRef: string;
  validFrom: string | null;
  validTo: string | null;
}

export interface GovernanceCandidates {
  duplicateGroups: Array<{ type: MemoryType; entries: MemoryEntry[] }>;
  conflictGroups: Array<{ type: MemoryType; title: string; entries: MemoryEntry[] }>;
}

export function searchMemories(filters: MemorySearchFilters): Promise<MemoryPage> {
  const params = new URLSearchParams({
    page: String(filters.page),
    size: String(filters.size),
  });
  if (filters.type) params.set('type', filters.type);
  if (filters.status) params.set('status', filters.status);
  if (filters.indexStatus) params.set('indexStatus', filters.indexStatus);
  return request(`/api/memories?${params.toString()}`);
}

export function fetchGovernanceCandidates(): Promise<GovernanceCandidates> {
  return request('/api/memories/governance/candidates?limit=50');
}

export function replaceMemory(memoryNo: string, form: MemoryForm): Promise<MemoryEntry> {
  return request(`/api/memories/${encodeURIComponent(memoryNo)}`, {
    method: 'PUT',
    body: form,
  });
}

export function expireMemory(memoryNo: string): Promise<MemoryEntry> {
  return request(`/api/memories/${encodeURIComponent(memoryNo)}/expire`, { method: 'POST' });
}

export function retryMemoryIndex(memoryNo: string): Promise<MemoryEntry> {
  return request(`/api/memories/${encodeURIComponent(memoryNo)}/retry-index`, {
    method: 'POST',
  });
}

export function markMemoryConflict(
  memoryNos: string[],
): Promise<{ entries: MemoryEntry[] }> {
  return request('/api/memories/governance/conflicts', {
    method: 'POST',
    body: { memoryNos },
  });
}

export function activateMemory(memoryNo: string): Promise<MemoryEntry> {
  return request(`/api/memories/${encodeURIComponent(memoryNo)}/activate`, {
    method: 'POST',
  });
}
