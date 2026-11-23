import React, {useMemo, useState} from 'react';
import {
  FlatList,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {ActivityEventRow} from '../components/ActivityEventRow';
import {colors, radius, spacing} from '../theme/theme';
import {ActivityEventType, ActivityLogEntry} from '../types/screenguard';

interface ActivityScreenProps {
  logs: ActivityLogEntry[];
  onClearLogs: () => void;
  onRefresh: () => void;
}

type FilterOption = 'ALL' | ActivityEventType;

const FILTERS: {key: FilterOption; label: string}[] = [
  {key: 'ALL', label: 'All Events'},
  {key: 'APP_DETECTED', label: 'App Detected'},
  {key: 'SCREENSHOT_SUCCESS', label: 'Capture OK'},
  {key: 'SCREENSHOT_FAILURE', label: 'Capture Failed'},
  {key: 'CLASSIFICATION_COMPLETED', label: 'Classification'},
  {key: 'SUSPICIOUS_DETECTED', label: 'Suspicious'},
];

export function ActivityScreen({
  logs,
  onClearLogs,
  onRefresh,
}: ActivityScreenProps) {
  const [selectedFilter, setSelectedFilter] = useState<FilterOption>('ALL');

  const filteredLogs = useMemo(() => {
    if (selectedFilter === 'ALL') {
      return logs;
    }
    return logs.filter(item => item.type === selectedFilter);
  }, [logs, selectedFilter]);

  return (
    <View style={styles.container}>
      <View style={styles.headerBlock}>
        <View style={styles.headerTopRow}>
          <View>
            <Text style={styles.heading}>Activity History</Text>
            <Text style={styles.subheading}>
              Metadata-only log · Zero screenshot images stored to disk
            </Text>
          </View>
          <View style={styles.actionGroup}>
            <Pressable onPress={onRefresh} style={styles.actionBtn}>
              <Text style={styles.actionBtnText}>Refresh</Text>
            </Pressable>
            <Pressable
              onPress={onClearLogs}
              style={[styles.actionBtn, styles.clearBtn]}>
              <Text style={[styles.actionBtnText, {color: colors.danger}]}>
                Clear
              </Text>
            </Pressable>
          </View>
        </View>

        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.filterBar}>
          {FILTERS.map(f => {
            const active = selectedFilter === f.key;
            return (
              <Pressable
                key={f.key}
                onPress={() => setSelectedFilter(f.key)}
                style={[styles.filterPill, active && styles.filterPillActive]}>
                <Text
                  style={[
                    styles.filterPillText,
                    active && styles.filterPillTextActive,
                  ]}>
                  {f.label}
                </Text>
              </Pressable>
            );
          })}
        </ScrollView>
      </View>

      <FlatList
        data={filteredLogs}
        keyExtractor={item => item.id}
        contentContainerStyle={styles.listContent}
        renderItem={({item}) => <ActivityEventRow entry={item} />}
        ListEmptyComponent={
          <View style={styles.emptyBox}>
            <Text style={styles.emptyTitle}>No activity events recorded</Text>
            <Text style={styles.emptySubtitle}>
              When Screen Guard is enabled in Android Accessibility Settings,
              foreground application changes, screenshot capture durations, and
              classification metadata appear chronologically here.
            </Text>
          </View>
        }
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  headerBlock: {
    backgroundColor: colors.surface,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
    paddingTop: spacing.md,
    paddingBottom: spacing.sm,
  },
  headerTopRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: spacing.lg,
    marginBottom: spacing.sm,
  },
  heading: {
    fontSize: 17,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  subheading: {
    fontSize: 11.5,
    color: colors.textMuted,
    marginTop: 2,
  },
  actionGroup: {
    flexDirection: 'row',
    gap: spacing.xs + 2,
  },
  actionBtn: {
    paddingHorizontal: spacing.sm + 2,
    paddingVertical: 6,
    borderRadius: radius.sm,
    backgroundColor: colors.surfaceElevated,
    borderWidth: 1,
    borderColor: colors.border,
  },
  clearBtn: {
    borderColor: colors.dangerBorder,
    backgroundColor: colors.dangerSoft,
  },
  actionBtnText: {
    fontSize: 12,
    fontWeight: '600',
    color: colors.textSecondary,
  },
  filterBar: {
    paddingHorizontal: spacing.lg,
    gap: spacing.xs + 2,
    paddingVertical: 4,
  },
  filterPill: {
    paddingHorizontal: spacing.sm + 4,
    paddingVertical: 6,
    borderRadius: radius.pill,
    backgroundColor: colors.surfaceSubtle,
    borderWidth: 1,
    borderColor: colors.border,
  },
  filterPillActive: {
    backgroundColor: colors.accentSoft,
    borderColor: colors.accentBorder,
  },
  filterPillText: {
    fontSize: 12,
    fontWeight: '600',
    color: colors.textSecondary,
  },
  filterPillTextActive: {
    color: '#60A5FA',
  },
  listContent: {
    padding: spacing.lg,
    paddingBottom: spacing.xxl + 16,
  },
  emptyBox: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.xl,
    marginTop: spacing.md,
    alignItems: 'center',
  },
  emptyTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: colors.textPrimary,
    marginBottom: spacing.xs,
  },
  emptySubtitle: {
    fontSize: 13,
    color: colors.textSecondary,
    textAlign: 'center',
    lineHeight: 19,
  },
});
