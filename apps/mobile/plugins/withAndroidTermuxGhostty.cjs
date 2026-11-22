const {
  withAppBuildGradle,
  withGradleProperties,
  withProjectBuildGradle,
} = require("expo/config-plugins");

// Fork-only: the Android terminal renders through the termux-ghostty AARs published to mavenLocal().
// Their Compose 1.13 dependencies declare minCompileMinorSdk=1, so the app compiles against android-37.1,
// and they ship only these ABIs.
const ARCHITECTURES = "armeabi-v7a,arm64-v8a,x86_64";
const SUPPRESSED_COMPILE_SDKS = "37,37.1";

// :expo aggregates every autolinked module, so AGP's AAR metadata check also holds it to android-37.1.
const EXPO_COMPILE_SDK_MINOR = `
subprojects {
  if (name == 'expo') {
    plugins.withId('com.android.library') {
      androidComponents.finalizeDsl { android -> android.compileSdkMinor = 1 }
    }
  }
}
`;

function withProjectGradle(config) {
  return withProjectBuildGradle(config, (nextConfig) => {
    let { contents } = nextConfig.modResults;
    if (!contents.includes("mavenLocal()")) {
      contents = contents.replace(
        /allprojects\s*\{\s*repositories\s*\{/,
        (match) => `${match}\n    mavenLocal()`,
      );
    }
    if (!contents.includes("android.compileSdkMinor = 1")) {
      contents = `${contents.trimEnd()}\n${EXPO_COMPILE_SDK_MINOR}`;
    }
    nextConfig.modResults.contents = contents;
    return nextConfig;
  });
}

function withCompileSdkMinor(config) {
  return withAppBuildGradle(config, (nextConfig) => {
    const { contents } = nextConfig.modResults;
    if (!/^\s*compileSdkMinor\b/m.test(contents)) {
      nextConfig.modResults.contents = contents.replace(
        /^(\s*)compileSdk rootProject\.ext\.compileSdkVersion\s*$/m,
        (match, indent) => `${match}\n${indent}compileSdkMinor 1`,
      );
    }
    return nextConfig;
  });
}

function withTermuxGhosttyProperties(config) {
  return withGradleProperties(config, (nextConfig) => {
    const overrides = {
      "android.suppressUnsupportedCompileSdk": SUPPRESSED_COMPILE_SDKS,
      reactNativeArchitectures: ARCHITECTURES,
    };
    const properties = nextConfig.modResults.filter(
      (item) => !(item.type === "property" && item.key in overrides),
    );
    for (const [key, value] of Object.entries(overrides)) {
      properties.push({ type: "property", key, value });
    }
    nextConfig.modResults = properties;
    return nextConfig;
  });
}

module.exports = function withAndroidTermuxGhostty(config) {
  return withTermuxGhosttyProperties(withCompileSdkMinor(withProjectGradle(config)));
};
