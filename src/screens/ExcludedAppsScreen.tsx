import React, {useEffect, useMemo, useState} from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import {colors, radius, spacing, typography} from '../theme/theme';
import {getFriendlyAppName} from '../utils/friendlyFormat';
import {InstalledApp} from '../types/screenguard';

interface Props {
  excludedPackages: string[];
  onLoadApps: () => Promise<InstalledApp[]>;
  onUpdateExcludedPackages: (packages: string[]) => Promise<void>;
  onClose: () => void;
}

const AVATAR_COLORS = [
  '#1447C8',
  '#0C7A6B',
  '#8A5A00',
  '#7A3BA8',
  '#B4462F',
  '#2B6CB0',
];

function avatarColor(packageName: string): string {
  let hash = 0;
  for (let i = 0; i < packageName.length; i += 1) {
    hash = (hash * 31 + packageName.charCodeAt(i)) % 100000;
  }
  return AVATAR_COLORS[hash % AVATAR_COLORS.length];
}

function initial(label: string): string {
  return (label.trim()[0] || '?').toUpperCase();
}

export function ExcludedAppsScreen({
  excludedPackages,
  onLoadApps,
  onUpdateExcludedPackages,
  onClose,
}: Props) {
  const [apps, setApps] = useState<InstalledApp[]>([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [showSystem, setShowSystem] = useState(false);
  const [customPackage, setCustomPackage] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let active = true;
    onLoadApps()
      .then(list => {
        if (active) {
          setApps(list);
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, [onLoadApps]);

  const excludedSet = useMemo(
    () => new Set(excludedPackages.map(pkg => pkg.toLowerCase())),
    [excludedPackages],
  );

  const visibleApps = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return apps.filter(app => {
      if (!showSystem && app.isSystem) {
        return false;
      }
      if (!needle) {
        return true;
      }
      return (
        app.label.toLowerCase().includes(needle) ||
        app.packageName.toLowerCase().includes(needle)
      );
    });
  }, [apps, query, showSystem]);

  const update = async (next: string[]) => {
    setBusy(true);
    try {
      await onUpdateExcludedPackages(next);
    } finally {
      setBusy(false);
    }
  };

  const toggleApp = async (app: InstalledApp) => {
    const pkg = app.packageName.toLowerCase();
    const next = excludedSet.has(pkg)
      ? excludedPackages.filter(item => item.toLowerCase() !== pkg)
      : [...excludedPackages, pkg];
    await update(next);
  };

  const addCustomPackage = async () => {
    const pkg = customPackage.trim().toLowerCase();
    if (!pkg || pkg.includes(' ')) {
      return;
    }
    if (!excludedSet.has(pkg)) {
      await update([...excludedPackages, pkg]);
    }
    setCustomPackage('');
  };

  const renderApp = ({item}: {item: InstalledApp}) => {
    const isExcluded = excludedSet.has(item.packageName.toLowerCase());
    return (
      <Pressable
        disabled={busy}
        onPress={() => toggleApp(item)}
        style={[styles.row, isExcluded && styles.rowExcluded]}>
        <View
          style={[
            styles.avatar,
            {backgroundColor: avatarColor(item.packageName)},
          ]}>
          <Text style={styles.avatarText}>{initial(item.label)}</Text>
        </View>
        <View style={styles.rowText}>
          <Text style={styles.rowLabel} numberOfLines={1}>
            {item.label}
          </Text>
          <Text style={styles.rowPackage} numberOfLines={1}>
            {item.packageName}
          </Text>
        </View>
        <View style={[styles.check, isExcluded && styles.checkActive]}>
          {isExcluded ? <Text style={styles.checkMark}>✓</Text> : null}
        </View>
      </Pressable>
    );
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Pressable onPress={onClose} style={styles.backButton} hitSlop={10}>
          <Text style={styles.backText}>←</Text>
        </Pressable>
        <View style={styles.headerText}>
          <Text style={styles.headerTitle}>Choose Excluded Apps</Text>
          <Text style={styles.headerSubtitle}>
            Screen Guard will skip these apps entirely
          </Text>
        </View>
      </View>

      <View style={styles.toolbar}>
        <TextInput
          value={query}
          onChangeText={setQuery}
          placeholder="Search app name or package"
          placeholderTextColor={colors.textMuted}
          autoCapitalize="none"
          autoCorrect={false}
          clearButtonMode="while-editing"
          style={styles.searchInput}
        />

        <View style={styles.filterRow}>
          <Text style={styles.filterLabel}>Show system apps</Text>
          <Switch
            value={showSystem}
            onValueChange={setShowSystem}
            trackColor={{false: colors.borderStrong, true: colors.royalBlue}}
            thumbColor="#FFFFFF"
          />
        </View>

        <View style={styles.summaryRow}>
          <Text style={styles.summaryText}>
            {excludedPackages.length} app
            {excludedPackages.length === 1 ? '' : 's'} excluded
            {showSystem ? '' : ` · ${apps.filter(a => !a.isSystem).length} user apps`}
          </Text>
          {excludedPackages.length > 0 ? (
            <Pressable
              disabled={busy}
              onPress={() => update([])}
              style={styles.clearButton}>
              <Text style={styles.clearButtonText}>Clear all</Text>
            </Pressable>
          ) : null}
        </View>
      </View>

      {loading ? (
        <View style={styles.loadingBox}>
          <ActivityIndicator color={colors.royalBlue} />
          <Text style={styles.loadingText}>Loading installed apps…</Text>
        </View>
      ) : (
        <FlatList
          data={visibleApps}
          keyExtractor={item => item.packageName}
          renderItem={renderApp}
          contentContainerStyle={styles.listContent}
          ListEmptyComponent={
            <View style={styles.emptyBox}>
              <Text style={styles.emptyTitle}>No apps found</Text>
              <Text style={styles.emptyText}>
                Try a different search term, enable system apps, or add a package
                name manually below.
              </Text>
            </View>
          }
        />
      )}

      <View style={styles.manualBox}>
        <Text style={styles.manualLabel}>Add a package manually</Text>
        <View style={styles.manualRow}>
          <TextInput
            value={customPackage}
            onChangeText={setCustomPackage}
            placeholder="com.example.app"
            placeholderTextColor={colors.textMuted}
            autoCapitalize="none"
            autoCorrect={false}
            style={styles.manualInput}
            onSubmitEditing={addCustomPackage}
          />
          <Pressable
            disabled={busy || !customPackage.trim()}
            onPress={addCustomPackage}
            style={[
              styles.manualButton,
              (!customPackage.trim() || busy) && styles.disabled,
            ]}>
            <Text style={styles.manualButtonText}>Add</Text>
          </Pressable>
        </View>
        <Text style={styles.manualHint}>
          Useful for work-profile or hidden apps that cannot be listed.
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: colors.background},
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    backgroundColor: colors.royalBlue,
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.sm,
    paddingBottom: spacing.lg,
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
  headerTitle: {
    fontSize: 18,
    fontWeight: '800',
    color: colors.textOnBlue,
  },
  headerSubtitle: {
    fontSize: 12,
    color: colors.textOnBlueMuted,
    marginTop: 2,
  },
  toolbar: {
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.md,
    paddingBottom: spacing.sm,
    backgroundColor: colors.surface,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
  searchInput: {
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: spacing.md,
    paddingVertical: 10,
    fontSize: 13,
    color: colors.textPrimary,
  },
  filterRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: spacing.sm,
  },
  filterLabel: {fontSize: 12.5, color: colors.textSecondary},
  summaryRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: spacing.sm,
  },
  summaryText: {fontSize: 12, fontWeight: '700', color: colors.textSecondary},
  clearButton: {
    paddingHorizontal: 12,
    paddingVertical: 5,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.dangerBorder,
  },
  clearButtonText: {fontSize: 11.5, fontWeight: '700', color: colors.danger},
  listContent: {paddingHorizontal: spacing.lg, paddingTop: spacing.sm},
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingVertical: 10,
    paddingHorizontal: spacing.sm,
    borderRadius: radius.lg,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  rowExcluded: {backgroundColor: colors.successSoft},
  avatar: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarText: {color: '#FFFFFF', fontSize: 15, fontWeight: '800'},
  rowText: {flex: 1},
  rowLabel: {fontSize: 13.5, fontWeight: '700', color: colors.textPrimary},
  rowPackage: {
    marginTop: 2,
    fontSize: 10.5,
    color: colors.textMuted,
    fontFamily: typography.mono,
  },
  check: {
    width: 22,
    height: 22,
    borderRadius: 6,
    borderWidth: 2,
    borderColor: colors.borderStrong,
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkActive: {backgroundColor: colors.royalBlue, borderColor: colors.royalBlue},
  checkMark: {color: '#FFFFFF', fontSize: 13, fontWeight: '800'},
  loadingBox: {flex: 1, alignItems: 'center', justifyContent: 'center', gap: 10},
  loadingText: {fontSize: 12.5, color: colors.textSecondary},
  emptyBox: {paddingVertical: spacing.xxl, alignItems: 'center'},
  emptyTitle: {fontSize: 14, fontWeight: '700', color: colors.textPrimary},
  emptyText: {
    marginTop: 6,
    fontSize: 12,
    lineHeight: 17,
    color: colors.textSecondary,
    textAlign: 'center',
  },
  manualBox: {
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    backgroundColor: colors.surface,
    borderTopWidth: 1,
    borderTopColor: colors.border,
  },
  manualLabel: {fontSize: 12.5, fontWeight: '700', color: colors.textSecondary},
  manualRow: {flexDirection: 'row', gap: spacing.sm, marginTop: spacing.sm},
  manualHint: {
    marginTop: 6,
    fontSize: 11,
    lineHeight: 15,
    color: colors.textMuted,
  },
  manualInput: {
    flex: 1,
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: spacing.md,
    paddingVertical: 9,
    fontSize: 12.5,
    fontFamily: typography.mono,
    color: colors.textPrimary,
  },
  manualButton: {
    backgroundColor: colors.royalBlue,
    borderRadius: radius.md,
    paddingHorizontal: spacing.lg,
    alignItems: 'center',
    justifyContent: 'center',
  },
  manualButtonText: {color: '#FFFFFF', fontSize: 12.5, fontWeight: '800'},
  disabled: {opacity: 0.5},
});
