#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp_dir="$(mktemp -d)"
old_password="${SYNCSHARE_TLS_PASSWORD-}"
restore() {
  if [[ -n "$old_password" ]]; then
    export SYNCSHARE_TLS_PASSWORD="$old_password"
  else
    unset SYNCSHARE_TLS_PASSWORD
  fi
  rm -rf "$tmp_dir"
}
trap restore EXIT

mvn -q -DskipTests compile
mkdir -p target/resume-test
javac --release 21 -cp target/classes -d target/resume-test tools/ResumeStorageSelfTest.java

export SYNCSHARE_TLS_PASSWORD="$(openssl rand -hex 20)"
keytool -genkeypair -noprompt -alias test -keyalg RSA -keysize 2048 -validity 2 \
  -dname 'CN=SyncShare-Test' -storetype PKCS12 \
  -keystore "$tmp_dir/identity.p12" -storepass:env SYNCSHARE_TLS_PASSWORD \
  -keypass:env SYNCSHARE_TLS_PASSWORD
keytool -exportcert -alias test -keystore "$tmp_dir/identity.p12" \
  -storepass:env SYNCSHARE_TLS_PASSWORD -file "$tmp_dir/test.cer"
keytool -importcert -noprompt -alias test -file "$tmp_dir/test.cer" \
  -keystore "$tmp_dir/trusted-peers.p12" -storetype PKCS12 \
  -storepass:env SYNCSHARE_TLS_PASSWORD

java "-Dsyncshare.keyStore=$tmp_dir/identity.p12" \
  "-Dsyncshare.trustStore=$tmp_dir/trusted-peers.p12" \
  -cp 'target/classes:target/resume-test' ResumeStorageSelfTest "$tmp_dir/test.cer"
