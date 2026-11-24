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
import {ScreenGuardLogo} from '../components/ScreenGuardLogo';
import {colors, radius, spacing} from '../theme/theme';
import {ActivityLogEntry} from '../types/screenguard';

interface ActivityScreenProps {
  logs: ActivityLogEntry[];
  onClearLogs: () => void;
  onRefresh: () => void;
}

type SimpleFilter = 'ALL' | 'BLOCKED' | 'CHECKS' | 'APPS';

const FILTERS: {key: SimpleFilter; label: string}[] = [
  {key: 'ALL', label: 'All Activity'},
  {key: 'BLOCKED', label: 'Blocked'},
  {key: 'CHECKS', label: 'Screen Checks'},
  {key: 'APPS', label: 'Apps'},
];

export function ActivityScreen({
  logs,
  onClearLogs,
  onRefresh,
}: ActivityScreenProps) {
  const [selectedFilter, setSelectedFilter] = useState<SimpleFilter>('ALL');

  const filteredLogs = useMemo(() => {
    switch (selectedFilter) {
      case 'BLOCKED':
        return logs.filter(item => item.type === 'SUSPICIOUS_DETECTED');
      case 'CHECKS':
        return logs.filter(
          item =>
            item.type === 'SCREENSHOT_SUCCESS' ||
            item.type === 'CLASSIFICATION_COMPLETED',
        );
      case 'APPS':
        return logs.filter(item => item.type === 'APP_DETECTED');
      case 'ALL':
      default:
        return logs;
    }
  }, [logs, selectedFilter]);

  return (
    <View style={styles.container}>
      <View style={styles.royalHeader}>
        <View style={styles.headerTopRow}>
          <View style={styles.titleWrap}>
            <ScreenGuardLogo size={32} />
            <View>
              <Text style={styles.heading}>Activity</Text>
              <Text style={styles.subheading}>
                Private, on-device protection log
              </Text>
            </View>
          </View>
          <View style={styles.actionGroup}>
            <Pressable onPress={onRefresh} style={styles.headerBtn}>
              <Text style={styles.headerBtnText}>Refresh</Text>
            </Pressable>
            <Pressable onPress={onClearLogs} style={styles.headerClearBtn}>
              <Text style={styles.headerClearText}>Clear</Text>
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
            <Text style={styles.emptyTitle}>No activity yet</Text>
            <Text style={styles.emptySubtitle}>
              When Screen Guard is active, blocked content and protected app
              checks will appear here. No screenshots are ever saved.
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
  royalHeader: {
    backgroundColor: colors.royalBlue,
    paddingTop: spacing.sm,
    paddingBottom: spacing.md,
    borderBottomLeftRadius: radius.xl,
    borderBottomRightRadius: radius.xl,
  },
  headerTopRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: spacing.lg,
    marginBottom: spacing.md,
  },
  titleWrap: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm + 2,
  },
  heading: {
    fontSize: 20,
    fontWeight: '800',
    color: colors.textOnBlue,
  },
  subheading: {
    fontSize: 12,
    color: colors.textOnBlueMuted,
    marginTop: 1,
  },
  actionGroup: {
    flexDirection: 'row',
    gap: spacing.xs + 2,
  },
  headerBtn: {
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: radius.pill,
    backgroundColor: colors.royalBlueGlass,
  },
  headerBtnText: {
    fontSize: 12,
    fontWeight: '700',
    color: '#FFFFFF',
  },
  headerClearBtn: {
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: radius.pill,
    backgroundColor: 'rgba(229, 57, 53, 0.22)',
  },
  headerClearText: {
    fontSize: 12,
    fontWeight: '700',
    color: '#FFD4D3',
  },
  filterBar: {
    paddingHorizontal: spacing.lg,
    gap: spacing.xs + 2,
  },
  filterPill: {
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: radius.pill,
    backgroundColor: colors.royalBlueGlass,
  },
  filterPillActive: {
    backgroundColor: colors.gold,
  },
  filterPillText: {
    fontSize: 12.5,
    fontWeight: '700',
    color: '#FFFFFF',
  },
  filterPillTextActive: {
    color: colors.goldText,
  },
  listContent: {
    padding: spacing.lg,
    paddingBottom: spacing.xxl + 16,
  },
  emptyBox: {
    backgroundColor: colors.surface,
    borderRadius: radius.xl,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.xl,
    marginTop: spacing.md,
    alignItems: 'center',
  },
  emptyTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: colors.textPrimary,
    marginBottom: spacing.xs,
  },
  emptySubtitle: {
    fontSize: 13.5,
    color: colors.textSecondary,
    textAlign: 'center',
    lineHeight: 20,
  },
});
