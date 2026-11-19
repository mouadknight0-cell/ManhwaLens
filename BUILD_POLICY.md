# Per-request Android build policy

After each implementation request, on the current Arena branch:

1. Increment `versionCode` above the last distributed APK in this update lineage and increment `versionName`. Never change `applicationId` (`com.manhwalens.app`). Current checkout: **0.10.1 / 30**. Before integrating a newer branch, check its version too; this checkout is based on the older 0.10.0 lineage.
2. Run unit tests and assemble an APK in GitHub Actions; publish the run link and actual result. Never describe an unverified artifact as a verified build.
3. Preserve the exact signing key across builds. Android updates require the same package ID, compatible signing certificate, and increasing versionCode. A randomly regenerated CI debug keystore breaks this contract.
4. Distribute APK metadata with version, commit, SHA-256 and signer certificate. Compare signer SHA-256 against the previously installed APK before claiming update compatibility. A persistent key alone does not establish compatibility with older APKs signed with a different key.
5. Do not uninstall the installed app as an automatic fix: that can delete user data. Installing a new APK is a manual update, not an automatic in-app updater.

## One-time protected signing setup

Configure these repository Actions secrets through GitHub settings (not chat, Git, logs, caches, or public artifacts):

- `MANHWALENS_UPDATE_KEYSTORE_BASE64`: base64 of the **existing trusted keystore** matching the installed APK when available.
- `MANHWALENS_UPDATE_STORE_PASSWORD`
- `MANHWALENS_UPDATE_KEY_ALIAS`
- `MANHWALENS_UPDATE_KEY_PASSWORD`

The workflow restores the key only into the temporary runner directory, builds using it, emits only public certificate fingerprints, and removes the file in an always-run cleanup. Back up the original key securely outside GitHub. Do not rotate it casually. Fork PRs do not receive repository secrets.

If no key secret exists, CI still builds a clearly marked **ephemeral-debug-key / NOT-update-guaranteed** development APK. This is build verification only, not fulfillment of the persistent-update guarantee. Do not start a new installation lineage from it expecting future in-place updates.

Current integration returned HTTP 403 when listing Actions secrets; this session cannot verify or provision repository signing secrets. No existing signing key is present in this checkout. Compatibility with previously installed APKs is therefore not established.

## Device verification before calling a lineage update-ready

- Compare `apksigner verify --print-certs` certificate SHA-256 for old/new APKs.
- Compare package ID and versionCode via APK Analyzer / aapt.
- Install old APK, configure settings, then install new APK with `adb install -r new.apk` (no uninstall); verify settings and permissions remain usable.
- Repeat with a subsequent higher-version APK signed with the same key.
