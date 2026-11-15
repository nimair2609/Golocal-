# GoLocal Automatic GitHub APK Build & In-App Update Guide

## 1. Required GitHub Repository Secrets
Navigate to **GitHub Repo → Settings → Secrets and variables → Actions → New repository secret** and add your existing production upload keystore credentials:

| Secret Name | Required | Description |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | Yes | Base64 of your original `.jks` / `.keystore` file (`base64 -w 0 my-upload-key.jks`) |
| `STORE_PASSWORD` | Yes | Keystore store password |
| `KEY_PASSWORD` | Yes | Signing key password |
| `KEY_ALIAS` | Optional | Your key alias if different from `upload` |
| `GOOGLE_SERVICES_JSON_BASE64` | Optional | Base64 of `app/google-services.json` if not tracked in Git |
| `ADMIN_WEBHOOK_URL` | Optional | Slack/Discord/webhook URL for build success/failure alerts |
| `SMTP_SERVER`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD` | Optional | SMTP credentials for emailing `renukadas083@gmail.com` (or `ADMIN_NOTIFY_EMAIL`) |

> **Security Policy:** Never commit keystores or passwords to Git. The workflow automatically fails if signing secrets are missing rather than generating ephemeral keys, guaranteeing continuous signature compatibility for seamless in-app APK upgrades.

## 2. How Automatic & Manual Builds Work
- **Automatic:** Pushing to `main` (or pushing a `v*` tag) triggers `.github/workflows/android-apk-release.yml`.
- **Manual:** Open **GitHub → Actions → GoLocal Android Release APK & Update Manifest → Run workflow** to specify `version_name`, `release_notes`, `is_mandatory`, and `min_supported_version_code`.
- Each build preserves `com.aistudio.golocal.wbmxrq`, runs `:app:testDebugUnitTest`, signs `app-release.apk`, verifies package ID and signature with `apksigner` / `aapt2`, creates `SHA256SUMS.txt` + `update-manifest.json`, and publishes them to **GitHub Releases**.
- Build logs and test reports are retained for 30 days as workflow artifacts on both success and failure.

## 3. Enabling In-App Updates in GoLocal Admin Portal
1. Open **GoLocal Admin → Settings → APK Update Management**.
2. Set **HTTPS Update Manifest URL** to:
   `https://github.com/<YOUR_ORG_OR_USER>/<YOUR_REPO>/releases/latest/download/update-manifest.json`
   (or fill Version Name, Version Code, APK URL, SHA-256, Release Notes, and Mandatory status directly).
3. Toggle **Enable In-App APK Updates** on and tap **Save APK Update Manifest**.
