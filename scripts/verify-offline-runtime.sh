#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "Offline runtime policy violation: $*" >&2
  exit 1
}

manifest="app/src/main/AndroidManifest.xml"
[[ -f "$manifest" ]] || fail "missing $manifest"

if grep -Eq 'android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|CHANGE_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_WIFI_STATE)' "$manifest"; then
  fail "network permission declared in production manifest"
fi

runtime_roots=(
  app/src/main
  core-common/src/main
  core-usb/src/main
  feature-firmware/src/main
  feature-recovery/src/main
  feature-storage/src/main
  feature-device/src/main
)

existing_roots=()
for root in "${runtime_roots[@]}"; do
  [[ -d "$root" ]] && existing_roots+=("$root")
done

if ((${#existing_roots[@]} > 0)); then
  if grep -RInE --include='*.kt' --include='*.java' --include='*.xml' \
      '(java\.net\.|javax\.net\.|android\.net\.(ConnectivityManager|NetworkCapabilities)|okhttp3\.|retrofit2\.|io\.ktor\.client|com\.android\.volley|WebView|http://|https://)' \
      "${existing_roots[@]}"; then
    fail "network API, client library, WebView, or remote URL found in runtime source"
  fi
fi

if grep -RInE --include='*.gradle' --include='*.gradle.kts' --include='libs.versions.toml' \
    '(okhttp|retrofit|ktor-client|volley|cronet|apache-httpclient)' . \
    --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle; then
  fail "network client dependency found in Gradle configuration"
fi

echo "Offline runtime policy verified."
