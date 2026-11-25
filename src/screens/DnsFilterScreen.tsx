import React, {useState} from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import {colors, radius, spacing} from '../theme/theme';
import {
  DnsFilterConfigInput,
  DnsFilterProfile,
  DnsFilterStatus,
} from '../types/screenguard';

interface Props {
  status: DnsFilterStatus;
  protectionLocked?: boolean;
  onRequestPermission: () => Promise<boolean>;
  onStart: () => Promise<DnsFilterStatus | null>;
  onStop: () => Promise<DnsFilterStatus | null>;
  onUpdateConfig: (
    config: DnsFilterConfigInput,
  ) => Promise<DnsFilterStatus | null>;
  onClose: () => void;
}

const PROFILES: {key: DnsFilterProfile; title: string; detail: string}[] = [
  {
    key: 'CLOUDFLARE_FAMILY',
    title: 'Cloudflare Family',
    detail: '1.1.1.3 · blocks malware and adult domains',
  },
  {
    key: 'ADGUARD_FAMILY',
    title: 'AdGuard Family',
    detail: '94.140.14.15 · adult content and trackers',
  },
  {
    key: 'CLEANBROWSING_ADULT',
    title: 'CleanBrowsing Adult',
    detail: '185.228.168.10 · adult content filter',
  },
  {
    key: 'OPENDNS_FAMILYSHIELD',
    title: 'OpenDNS FamilyShield',
    detail: '208.67.222.123 · preconfigured adult blocking',
  },
  {
    key: 'CUSTOM',
    title: 'Custom DNS server',
    detail: 'Use your own DNS resolver address',
  },
];

function parseDomains(input: string): string[] {
  return input
    .split(/[\s,;]+/)
    .map(item => item.trim().toLowerCase().replace(/^www\./, ''))
    .filter(item => /^[a-z0-9.-]+\.[a-z]{2,}$/i.test(item));
}

function formatEventTime(timestampMs: number): string {
  if (!timestampMs) {
    return 'No events yet';
  }
  const date = new Date(timestampMs);
  return `${date.toLocaleDateString()} ${date.toLocaleTimeString()}`;
}

export function DnsFilterScreen({
  status,
  protectionLocked = false,
  onRequestPermission,
  onStart,
  onStop,
  onUpdateConfig,
  onClose,
}: Props) {
  const [busy, setBusy] = useState(false);
  const [draftProfile, setDraftProfile] = useState<DnsFilterProfile>(
    status.profile,
  );
  const [draftHost, setDraftHost] = useState(
    status.profile === 'CUSTOM' ? status.upstream : '',
  );
  const [domainText, setDomainText] = useState('');
  const [draftStarter, setDraftStarter] = useState(status.useStarterList);

  const runAction = async (action: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await action();
    } finally {
      setBusy(false);
    }
  };

  const saveConfig = async (overrides: Partial<DnsFilterConfigInput> = {}) => {
    const config: DnsFilterConfigInput = {
      profile: overrides.profile ?? draftProfile,
      customHost: overrides.customHost ?? draftHost,
      customDomains: overrides.customDomains ?? status.customDomains,
      useStarterList: overrides.useStarterList ?? draftStarter,
    };
    await onUpdateConfig(config);
  };

  const chooseProfile = (profile: DnsFilterProfile) => {
    setDraftProfile(profile);
    void runAction(() => saveConfig({profile}));
  };

  const saveCustomHost = () => {
    const host = draftHost.trim().toLowerCase();
    if (!host || !/^[a-z0-9.-]+$/i.test(host)) {
      return;
    }
    void runAction(() => saveConfig({profile: 'CUSTOM', customHost: host}));
  };

  const addDomains = () => {
    const parsed = parseDomains(domainText);
    if (parsed.length === 0) {
      return;
    }
    const nextDomains = Array.from(new Set([...status.customDomains, ...parsed]));
    void runAction(async () => {
      await saveConfig({customDomains: nextDomains});
      setDomainText('');
    });
  };

  const removeDomain = (domain: string) => {
    const nextDomains = status.customDomains.filter(item => item !== domain);
    void runAction(() => saveConfig({customDomains: nextDomains}));
  };

  const toggleStarterList = () => {
    const next = !draftStarter;
    setDraftStarter(next);
    void runAction(() => saveConfig({useStarterList: next}));
  };

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={styles.content}
      keyboardShouldPersistTaps="handled"
      showsVerticalScrollIndicator={false}>
      <View style={styles.header}>
        <Pressable onPress={onClose} style={styles.backButton} hitSlop={10}>
          <Text style={styles.backText}>←</Text>
        </Pressable>
        <View style={styles.headerText}>
          <Text style={styles.headerTitle}>DNS Content Filter</Text>
          <Text style={styles.headerSubtitle}>
            Block explicit sites before a page loads
          </Text>
        </View>
      </View>

      <View style={styles.body}>
        <View style={styles.card}>
          <View style={styles.statusRow}>
            <View>
              <Text style={styles.cardTitle}>Filter Status</Text>
              <Text style={[styles.statusBadge, status.active ? styles.badgeActive : styles.badgeOff]}>
                {status.active ? 'ACTIVE' : 'OFF'}
              </Text>
            </View>
            <Text style={styles.upstreamLabel}>
              {status.active ? 'DNS routed locally' : 'Android DNS unchanged'}
            </Text>
          </View>

          <InfoRow label="Permission" value={status.prepared ? 'VPN approved' : 'Needs approval'} />
          <InfoRow label="Resolver" value={status.upstream} />
          <InfoRow label="Custom domains" value={`${status.customDomainCount}`} />
          <InfoRow label="Blocked / allowed / failed" value={`${status.blockedQueries} / ${status.allowedQueries} / ${status.failedQueries}`} />
          <InfoRow label="Last event" value={status.lastEvent.replace(/_/g, ' ')} />
          <InfoRow label="Event time" value={formatEventTime(status.lastEventTimestampMs)} />

          <Text style={styles.note}>{status.note}</Text>

          <Pressable
            disabled={busy || (status.active && protectionLocked)}
            onPress={() =>
              void runAction(async () => {
                if (!status.prepared) {
                  await onRequestPermission();
                } else if (status.active) {
                  await onStop();
                } else {
                  await onStart();
                }
              })
            }
            style={[
              styles.primaryButton,
              (busy || (status.active && protectionLocked)) && styles.disabled,
            ]}>
            <Text style={styles.primaryButtonText}>
              {!status.prepared
                ? 'Approve & Start DNS Filter'
                : status.active && protectionLocked
                  ? 'DNS Filter Locked (Unlock in Protect)'
                  : status.active
                    ? 'Stop DNS Filter'
                    : 'Start DNS Filter'}
            </Text>
          </Pressable>

          <Text style={styles.helper}>
            {status.active
              ? 'Android shows a VPN indicator. Stop any time here, from the notification, or in Android Settings → VPN.'
              : 'Android asks for permission before the filter can start. Screen Guard cannot enable it silently.'}
          </Text>
        </View>

        <View style={styles.card}>
          <Text style={styles.cardTitle}>Filtering Preset</Text>
          <Text style={styles.cardSub}>
            The resolver blocks known adult and malicious domains before your apps connect to them.
          </Text>

          {PROFILES.map(profile => {
            const selected = draftProfile === profile.key;
            return (
              <Pressable
                key={profile.key}
                disabled={busy}
                onPress={() => chooseProfile(profile.key)}
                style={[styles.profileRow, selected && styles.profileRowSelected]}>
                <View style={[styles.radio, selected && styles.radioSelected]}>
                  {selected ? <Text style={styles.radioDot} /> : null}
                </View>
                <View style={styles.profileText}>
                  <Text style={[styles.profileTitle, selected && styles.profileTitleSelected]}>
                    {profile.title}
                  </Text>
                  <Text style={styles.profileDetail}>{profile.detail}</Text>
                </View>
              </Pressable>
            );
          })}

          {draftProfile === 'CUSTOM' ? (
            <View style={styles.customBox}>
              <Text style={styles.fieldLabel}>Custom DNS server address</Text>
              <View style={styles.inputRow}>
                <TextInput
                  value={draftHost}
                  onChangeText={setDraftHost}
                  placeholder="1.1.1.3"
                  placeholderTextColor={colors.textMuted}
                  autoCapitalize="none"
                  autoCorrect={false}
                  style={styles.textInput}
                />
                <Pressable
                  disabled={busy || !draftHost.trim()}
                  onPress={saveCustomHost}
                  style={[styles.smallButton, busy && styles.disabled]}>
                  <Text style={styles.smallButtonText}>Save</Text>
                </Pressable>
              </View>
              <Text style={styles.helper}>IPv4 address or hostname. IPv6 and DNS-over-HTTPS are not supported here.</Text>
            </View>
          ) : null}
        </View>

        <View style={styles.card}>
          <Text style={styles.cardTitle}>Your Blocked Domains</Text>
          <Text style={styles.cardSub}>
            Add any site you want to block. Matching subdomains are blocked too. Domains are stored only on this device.
          </Text>

          <View style={styles.switchRow}>
            <View style={styles.switchTextCol}>
              <Text style={styles.rowTitle}>Built-in starter list</Text>
              <Text style={styles.rowSubtitle}>
                Include a small maintained list of well-known adult domains.
              </Text>
            </View>
            <Switch
              value={draftStarter}
              onValueChange={toggleStarterList}
              trackColor={{false: colors.borderStrong, true: colors.royalBlue}}
              thumbColor="#FFFFFF"
            />
          </View>

          <View style={styles.inputRow}>
            <TextInput
              value={domainText}
              onChangeText={setDomainText}
              placeholder="example.com, another.site"
              placeholderTextColor={colors.textMuted}
              autoCapitalize="none"
              autoCorrect={false}
              style={styles.textInput}
            />
            <Pressable
              disabled={busy || domainText.trim().length === 0}
              onPress={addDomains}
              style={[styles.smallButton, busy && styles.disabled]}>
              <Text style={styles.smallButtonText}>Add</Text>
            </Pressable>
          </View>

          {status.customDomains.length > 0 ? (
            <View style={styles.chipWrap}>
              {status.customDomains.map(domain => (
                <Pressable
                  key={domain}
                  disabled={busy}
                  onPress={() => removeDomain(domain)}
                  style={styles.domainChip}>
                  <Text style={styles.domainChipText}>{domain}</Text>
                  <Text style={styles.domainChipRemove}> ✕</Text>
                </Pressable>
              ))}
            </View>
          ) : (
            <Text style={styles.helper}>No custom domains yet. Tap a chip to remove it.</Text>
          )}
        </View>

        <View style={[styles.card, styles.warningCard]}>
          <Text style={styles.cardTitle}>What this can and cannot do</Text>
          <Text style={styles.warningText}>
            • Filters ordinary port-53 DNS requests only. It does not inspect or route your other traffic.
            {'\n'}• Apps using their own encrypted DNS, hardcoded IP addresses, or Android Private DNS can bypass it.
            {'\n'}• Your selected resolver provider can see DNS queries. Screen Guard stores no browsing history and uploads nothing itself.
            {'\n'}• This is a filter, not a guarantee of safety.
          </Text>
        </View>
      </View>
    </ScrollView>
  );
}

function InfoRow({label, value}: {label: string; value: string}) {
  return (
    <View style={styles.infoRow}>
      <Text style={styles.infoLabel}>{label}</Text>
      <Text style={styles.infoValue} numberOfLines={2}>
        {value}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: colors.background},
  content: {paddingBottom: spacing.xxl + 16},
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    backgroundColor: colors.royalBlue,
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.sm,
    paddingBottom: spacing.lg,
    borderBottomLeftRadius: radius.xl,
    borderBottomRightRadius: radius.xl,
  },
  backButton: {
    width: 34,
    height: 34,
    borderRadius: 17,
    backgroundColor: 'rgba(255,255,255,0.16)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  backText: {color: '#FFFFFF', fontSize: 20, fontWeight: '800'},
  headerText: {flex: 1},
  headerTitle: {fontSize: 18, fontWeight: '800', color: colors.textOnBlue},
  headerSubtitle: {fontSize: 12, color: colors.textOnBlueMuted, marginTop: 2},
  body: {padding: spacing.lg},
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.xl,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.lg,
    marginBottom: spacing.md,
  },
  warningCard: {backgroundColor: colors.warningSoft, borderColor: colors.warningBorder},
  cardTitle: {fontSize: 16, fontWeight: '800', color: colors.textPrimary, marginBottom: 6},
  cardSub: {fontSize: 12.5, lineHeight: 18, color: colors.textSecondary, marginBottom: spacing.md},
  statusRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: spacing.sm,
  },
  statusBadge: {
    alignSelf: 'flex-start',
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 1,
    paddingHorizontal: 9,
    paddingVertical: 3,
    borderRadius: radius.pill,
    overflow: 'hidden',
  },
  badgeActive: {color: colors.success, backgroundColor: colors.successSoft},
  badgeOff: {color: colors.textSecondary, backgroundColor: colors.neutralBadge},
  upstreamLabel: {fontSize: 11, color: colors.textMuted, marginTop: 4},
  infoRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    gap: spacing.md,
    paddingVertical: 7,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  infoLabel: {fontSize: 12, color: colors.textSecondary, flex: 1},
  infoValue: {fontSize: 12, fontWeight: '700', color: colors.textPrimary, flex: 1.4, textAlign: 'right'},
  note: {
    marginTop: spacing.md,
    padding: spacing.md,
    borderRadius: radius.md,
    backgroundColor: colors.warningSoft,
    fontSize: 11.5,
    lineHeight: 17,
    color: colors.textSecondary,
  },
  primaryButton: {
    backgroundColor: colors.royalBlue,
    borderRadius: radius.pill,
    paddingVertical: 13,
    alignItems: 'center',
    marginTop: spacing.md,
  },
  primaryButtonText: {color: '#FFFFFF', fontSize: 13.5, fontWeight: '800'},
  helper: {fontSize: 11, lineHeight: 16, color: colors.textMuted, marginTop: spacing.sm},
  profileRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingVertical: 10,
    paddingHorizontal: spacing.sm,
    borderRadius: radius.md,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  profileRowSelected: {backgroundColor: colors.accentSoft},
  radio: {width: 18, height: 18, borderRadius: 9, borderWidth: 2, borderColor: colors.borderStrong, alignItems: 'center', justifyContent: 'center'},
  radioSelected: {borderColor: colors.royalBlue},
  radioDot: {width: 8, height: 8, borderRadius: 4, backgroundColor: colors.royalBlue},
  profileText: {flex: 1},
  profileTitle: {fontSize: 13, fontWeight: '700', color: colors.textPrimary},
  profileTitleSelected: {color: colors.royalBlue},
  profileDetail: {fontSize: 11, color: colors.textMuted, marginTop: 2},
  customBox: {marginTop: spacing.md, paddingTop: spacing.md, borderTopWidth: 1, borderTopColor: colors.border},
  fieldLabel: {fontSize: 12, fontWeight: '700', color: colors.textSecondary, marginBottom: spacing.sm},
  switchRow: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.md, marginBottom: spacing.md},
  switchTextCol: {flex: 1},
  rowTitle: {fontSize: 13, fontWeight: '700', color: colors.textPrimary},
  rowSubtitle: {fontSize: 11, lineHeight: 15, color: colors.textMuted, marginTop: 2},
  inputRow: {flexDirection: 'row', gap: spacing.sm},
  textInput: {
    flex: 1,
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: spacing.md,
    paddingVertical: 10,
    fontSize: 12,
    color: colors.textPrimary,
  },
  smallButton: {
    backgroundColor: colors.royalBlue,
    borderRadius: radius.md,
    paddingHorizontal: spacing.lg,
    alignItems: 'center',
    justifyContent: 'center',
  },
  smallButtonText: {color: '#FFFFFF', fontSize: 12, fontWeight: '800'},
  chipWrap: {flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm, marginTop: spacing.md},
  domainChip: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.accentSoft,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.accentBorder,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  domainChipText: {fontSize: 11.5, fontWeight: '700', color: colors.royalBlue},
  domainChipRemove: {fontSize: 11, color: colors.danger},
  warningText: {fontSize: 11.5, lineHeight: 18, color: colors.textSecondary},
  disabled: {opacity: 0.5},
});
