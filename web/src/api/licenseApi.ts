import { request } from './http.ts';

export type LicenseRuntimeStatus =
  | 'UNCONFIGURED'
  | 'VALID'
  | 'GRACE'
  | 'EXPIRED'
  | 'INVALID_SIGNATURE'
  | 'MALFORMED';

export interface LicenseStatus {
  status: LicenseRuntimeStatus;
  statusLabel: string;
  configured: boolean;
  enforce: boolean;
  blocking: boolean;
  subject: string | null;
  issuedAt: string | null;
  expiresAt: string | null;
  graceUntil: string | null;
  seats: number | null;
  modules: string[];
  kid: string | null;
  message: string;
}

export function fetchLicenseStatus(): Promise<LicenseStatus> {
  return request<LicenseStatus>('/api/license/status');
}

export function licenseStatusTone(status: LicenseRuntimeStatus): 'ok' | 'warn' | 'info' | 'error' {
  switch (status) {
    case 'VALID':
      return 'ok';
    case 'GRACE':
      return 'warn';
    case 'UNCONFIGURED':
      return 'info';
    case 'EXPIRED':
    case 'INVALID_SIGNATURE':
    case 'MALFORMED':
      return 'error';
    default: {
      const exhaustive: never = status;
      return exhaustive;
    }
  }
}
