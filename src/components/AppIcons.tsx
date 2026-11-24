import React from 'react';
import {StyleSheet, Text, View} from 'react-native';

interface IconProps {
  color?: string;
  size?: number;
}

export function HomeIcon({color = '#1447C8', size = 20}: IconProps) {
  const roof = size * 0.52;
  return (
    <View style={[styles.center, {width: size, height: size}]}>
      <View
        style={{
          width: 0,
          height: 0,
          borderLeftWidth: roof,
          borderRightWidth: roof,
          borderBottomWidth: size * 0.42,
          borderLeftColor: 'transparent',
          borderRightColor: 'transparent',
          borderBottomColor: color,
        }}
      />
      <View
        style={{
          width: size * 0.68,
          height: size * 0.44,
          backgroundColor: color,
          borderBottomLeftRadius: 2,
          borderBottomRightRadius: 2,
          alignItems: 'center',
          justifyContent: 'flex-end',
        }}>
        <View
          style={{
            width: size * 0.22,
            height: size * 0.26,
            backgroundColor: '#FFFFFF',
            borderTopLeftRadius: 2,
            borderTopRightRadius: 2,
          }}
        />
      </View>
    </View>
  );
}

export function BarsIcon({color = '#0C2D84', size = 20}: IconProps) {
  const barW = Math.max(2.5, size * 0.17);
  return (
    <View
      style={{
        width: size,
        height: size,
        flexDirection: 'row',
        alignItems: 'flex-end',
        justifyContent: 'space-between',
        paddingHorizontal: size * 0.12,
        paddingVertical: size * 0.14,
      }}>
      <View
        style={{
          width: barW,
          height: size * 0.36,
          backgroundColor: color,
          borderRadius: 2,
        }}
      />
      <View
        style={{
          width: barW,
          height: size * 0.72,
          backgroundColor: color,
          borderRadius: 2,
        }}
      />
      <View
        style={{
          width: barW,
          height: size * 0.54,
          backgroundColor: color,
          borderRadius: 2,
        }}
      />
    </View>
  );
}

export function ShieldCheckIcon({color = '#0C2D84', size = 20}: IconProps) {
  return (
    <View
      style={[
        styles.center,
        {
          width: size * 0.84,
          height: size * 0.92,
          backgroundColor: color,
          borderTopLeftRadius: size * 0.22,
          borderTopRightRadius: size * 0.22,
          borderBottomLeftRadius: size * 0.48,
          borderBottomRightRadius: size * 0.48,
        },
      ]}>
      <Text
        style={{
          color: '#FFFFFF',
          fontSize: size * 0.5,
          fontWeight: '800',
          lineHeight: size * 0.56,
        }}>
        ✓
      </Text>
    </View>
  );
}

export function SettingsGearIcon({color = '#64748B', size = 20}: IconProps) {
  return (
    <View style={[styles.center, {width: size, height: size}]}>
      <Text
        style={{
          color,
          fontSize: size * 0.95,
          lineHeight: size * 1.05,
          textAlign: 'center',
        }}>
        ⚙
      </Text>
    </View>
  );
}

export function ClockIcon({color = '#0C2D84', size = 20}: IconProps) {
  return (
    <View
      style={[
        styles.center,
        {
          width: size * 0.86,
          height: size * 0.86,
          borderRadius: (size * 0.86) / 2,
          borderWidth: 2,
          borderColor: color,
        },
      ]}>
      <View
        style={{
          width: 2,
          height: size * 0.24,
          backgroundColor: color,
          position: 'absolute',
          top: size * 0.16,
        }}
      />
      <View
        style={{
          width: size * 0.22,
          height: 2,
          backgroundColor: color,
          position: 'absolute',
          right: size * 0.18,
          top: size * 0.39,
        }}
      />
    </View>
  );
}

export function BlockedCircleIcon({color = '#E53935', size = 28}: IconProps) {
  return (
    <View
      style={[
        styles.center,
        {
          width: size,
          height: size,
          borderRadius: size / 2,
          borderWidth: 2.2,
          borderColor: color,
          backgroundColor: 'rgba(229, 57, 53, 0.08)',
        },
      ]}>
      <View
        style={{
          width: size * 0.65,
          height: 2.2,
          backgroundColor: color,
          transform: [{rotate: '-45deg'}],
        }}
      />
    </View>
  );
}

export function CameraBadgeIcon({color = '#1E88E5', size = 28}: IconProps) {
  return (
    <View
      style={[
        styles.center,
        {
          width: size,
          height: size * 0.76,
          borderRadius: 6,
          backgroundColor: color,
        },
      ]}>
      <View
        style={{
          width: size * 0.34,
          height: size * 0.34,
          borderRadius: (size * 0.34) / 2,
          borderWidth: 2,
          borderColor: '#FFFFFF',
        }}
      />
    </View>
  );
}

export function PauseBarsIcon({color = '#0A1D4E', size = 16}: IconProps) {
  const barW = Math.max(3, size * 0.24);
  return (
    <View
      style={{
        width: size,
        height: size,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        gap: size * 0.2,
      }}>
      <View
        style={{
          width: barW,
          height: size * 0.8,
          backgroundColor: color,
          borderRadius: 1.5,
        }}
      />
      <View
        style={{
          width: barW,
          height: size * 0.8,
          backgroundColor: color,
          borderRadius: 1.5,
        }}
      />
    </View>
  );
}

export function PlayTriangleIcon({color = '#0A1D4E', size = 14}: IconProps) {
  return (
    <View
      style={{
        width: 0,
        height: 0,
        marginLeft: 2,
        borderTopWidth: size * 0.45,
        borderBottomWidth: size * 0.45,
        borderLeftWidth: size * 0.78,
        borderTopColor: 'transparent',
        borderBottomColor: 'transparent',
        borderLeftColor: color,
      }}
    />
  );
}

export function PulseIcon({color = '#0C2D84', size = 18}: IconProps) {
  return (
    <Text style={{color, fontSize: size * 0.95, fontWeight: '700'}}>〰</Text>
  );
}

const styles = StyleSheet.create({
  center: {
    alignItems: 'center',
    justifyContent: 'center',
  },
});
