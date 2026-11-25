import {AccountabilityStatus} from '../types/screenguard';

export function formatRemainingTime(ms: number): string {
  if (!ms || ms <= 0) {
    return 'Ended';
  }
  const totalSec = Math.floor(ms / 1000);
  const days = Math.floor(totalSec / 86400);
  const hours = Math.floor((totalSec % 86400) / 3600);
  const minutes = Math.floor((totalSec % 3600) / 60);
  const seconds = totalSec % 60;
  if (days > 0) {
    return `${days}d ${hours}h ${minutes}m`;
  }
  if (hours > 0) {
    return `${hours}h ${minutes}m ${seconds}s`;
  }
  if (minutes > 0) {
    return `${minutes}m ${seconds}s`;
  }
  return `${seconds}s`;
}

export function formatCommitmentLength(days: number): string {
  if (!days) {
    return 'Not set';
  }
  if (days === 1) {
    return '1 day';
  }
  if (days < 30) {
    return `${days} days`;
  }
  if (days % 30 === 0) {
    return `${days / 30} month${days === 30 ? '' : 's'}`;
  }
  return `${days} days`;
}

export function protectionModeLabel(status: AccountabilityStatus): string {
  if (!status.active) {
    return 'Off — no commitment';
  }
  switch (status.mode) {
    case 'MANAGED_DEVICE_OBSERVED':
      return 'Accountability + Device Owner observed';
    case 'ACCOUNTABILITY_DEVICE_ADMIN':
      return 'Accountability + Device administrator';
    default:
      return 'Accountability only (self-control)';
  }
}

export function deviceAdminLabel(status: AccountabilityStatus): string {
  return status.deviceAdminActive ? 'Active (lock-screen capability)' : 'Not active';
}

export function managedDeviceLabel(status: AccountabilityStatus): string {
  if (status.deviceOwnerActive) {
    return 'Device Owner enrolled';
  }
  if (status.profileOwnerActive) {
    return 'Work profile owner enrolled';
  }
  return 'Not enrolled — ordinary app';
}

export function enforcementLabel(status: AccountabilityStatus): string {
  if (status.uninstallEnforcementActive) {
    return 'Android-enforced uninstall policy observed';
  }
  if (status.deviceAdminActive) {
    return 'Device administrator support only — not uninstall protection';
  }
  return 'Not enforced by Android — voluntary commitment';
}

export function deactivationInstructions(status: AccountabilityStatus): string[] {
  const lines = [
    'Open Protect → Deactivate, then wait through the cooling-off period.',
    status.pinConfigured
      ? 'Enter your PIN or the emergency recovery code you saved when enabling.'
      : 'No PIN is required; confirmation plus the cooling-off period ends the commitment.',
    'If you cannot use the app, remove Screen Guard device administrator in Android Settings → Security → Device administrators (if active), then uninstall from Settings or Play Store.',
  ];
  return lines;
}

export function emergencyRecoverySummary(status: AccountabilityStatus): string {
  const hint = status.recoveryCodeHint
    ? ` Saved code hint: ${status.recoveryCodeHint}.`
    : '';
  return `Emergency recovery code written down at activation.${hint} Android recovery (deactivate admin → uninstall / clear app data / factory reset) always remains available.`;
}
