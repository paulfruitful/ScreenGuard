import React from 'react';
import {Image, ImageStyle, StyleSheet, View, ViewStyle} from 'react-native';

interface ScreenGuardLogoProps {
  size?: number;
  withHalo?: boolean;
  style?: ViewStyle;
}

const LOGO_SOURCE = require('../assets/shield_cross_logo.png');

export function ScreenGuardLogo({
  size = 36,
  withHalo = false,
  style,
}: ScreenGuardLogoProps) {
  if (!withHalo) {
    return (
      <Image
        source={LOGO_SOURCE}
        style={[{width: size, height: size}, style as ImageStyle]}
        resizeMode="contain"
      />
    );
  }

  const haloSize = size * 1.48;
  return (
    <View
      style={[
        styles.halo,
        {
          width: haloSize,
          height: haloSize,
          borderRadius: haloSize / 2,
        },
        style,
      ]}>
      <Image
        source={LOGO_SOURCE}
        style={{width: size, height: size}}
        resizeMode="contain"
      />
    </View>
  );
}

const styles = StyleSheet.create({
  halo: {
    backgroundColor: 'rgba(255, 255, 255, 0.09)',
    alignItems: 'center',
    justifyContent: 'center',
  },
});
