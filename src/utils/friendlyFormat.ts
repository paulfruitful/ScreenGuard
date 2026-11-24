import {ActivityLogEntry} from '../types/screenguard';

const APP_DISPLAY_NAMES: Record<string, string> = {
  'com.android.chrome': 'Chrome',
  'com.instagram.android': 'Instagram',
  'com.zhiliaoapp.musically': 'TikTok',
  'com.ss.android.ugc.trill': 'TikTok',
  'com.twitter.android': 'X',
  'com.google.android.youtube': 'YouTube',
  'com.facebook.katana': 'Facebook',
  'com.whatsapp': 'WhatsApp',
  'com.whatsapp.w4b': 'WhatsApp Business',
  'com.android.settings': 'Settings',
  'com.miui.home': 'Home Screen',
  'com.sec.android.app.launcher': 'Home Screen',
  'com.google.android.apps.nexuslauncher': 'Home Screen',
  'com.google.android.googlequicksearchbox': 'Google Search',
  'com.paulfruitful.screenguard': 'Screen Guard',
  'com.google.android.gm': 'Gmail',
  'com.google.android.apps.messaging': 'Messages',
  'com.samsung.android.messaging': 'Messages',
};

export function getFriendlyAppName(packageName?: string): string {
  if (!packageName || packageName === 'None' || packageName === 'none') {
    return 'Device';
  }
  if (APP_DISPLAY_NAMES[packageName]) {
    return APP_DISPLAY_NAMES[packageName];
  }
  const parts = packageName.split('.');
  const last = parts[parts.length - 1] || packageName;
  return last.charAt(0).toUpperCase() + last.slice(1);
}

export function formatRelativeTime(timestampMs: number): string {
  if (!timestampMs) {
    return 'Just now';
  }
  const diffSec = Math.max(0, Math.floor((Date.now() - timestampMs) / 1000));
  if (diffSec < 45) {
    return 'Just now';
  }
  if (diffSec < 3600) {
    const mins = Math.floor(diffSec / 60);
    return `${mins} ${mins === 1 ? 'minute' : 'minutes'} ago`;
  }
  if (diffSec < 86400) {
    const hrs = Math.floor(diffSec / 3600);
    return `${hrs} ${hrs === 1 ? 'hour' : 'hours'} ago`;
  }
  const days = Math.floor(diffSec / 86400);
  return `${days} ${days === 1 ? 'day' : 'days'} ago`;
}

export interface FriendlyActivityItem {
  kind: 'blocked' | 'captured' | 'app' | 'system' | 'warning';
  title: string;
  subtitle: string;
}

export function toFriendlyActivity(entry: ActivityLogEntry): FriendlyActivityItem {
  const appName = getFriendlyAppName(entry.packageName);
  const when = formatRelativeTime(entry.timestamp);

  if (entry.type === 'SUSPICIOUS_DETECTED') {
    return {
      kind: 'blocked',
      title: 'Inappropriate Content Blocked',
      subtitle: `${when} · ${appName}`,
    };
  }

  if (entry.type === 'SCREENSHOT_SUCCESS') {
    return {
      kind: 'captured',
      title: 'Screen Checked',
      subtitle: `${when} · Protected overlay ready in ${appName}`,
    };
  }

  if (entry.type === 'SCREENSHOT_FAILURE') {
    const isPrivate = entry.errorCode === 'SECURE_WINDOW';
    return {
      kind: 'warning',
      title: isPrivate ? 'Private Screen Skipped' : 'Screen Check Skipped',
      subtitle: `${when} · ${appName}`,
    };
  }

  if (entry.type === 'APP_DETECTED') {
    return {
      kind: 'app',
      title: `Opened ${appName}`,
      subtitle: `${when} · Active monitoring`,
    };
  }

  if (entry.type === 'CLASSIFICATION_COMPLETED') {
    return {
      kind: 'captured',
      title: 'Content Verified Safe',
      subtitle: `${when} · ${appName}`,
    };
  }

  return {
    kind: 'system',
    title: entry.title.includes('Paused')
      ? 'Protection Paused'
      : entry.title.includes('Enabled') || entry.title.includes('Connected')
      ? 'Protection Active'
      : entry.title.includes('Return to Safe')
      ? 'Returned to Safe Browsing'
      : entry.title.includes('Dismissed')
      ? 'Shield Dismissed for Screen'
      : 'Screen Guard Update',
    subtitle: `${when} · ${appName}`,
  };
}
