# PDF Werkstatt

PDF-Editor als Offline-Web-App und Android-App. Keine Verbindung nach aussen.

## Inhalt
| Datei/Ordner | Zweck |
|---|---|
| `pdf-werkstatt.html` | Die komplette App (pdf.js 6.4, pdf-lib, Schriften, CMaps, Decoder eingebettet) |
| `ocr/` | Texterkennung (tesseract.js, Deutsch + Englisch), wird nur bei OCR geladen |
| `sw.js`, `manifest.webmanifest`, `icon-*.png` | Installation als PWA, Offline-Betrieb, Dateizuordnung (Windows/macOS) |
| `android/` | Native Android-Hülle (WebView ohne Internet-Berechtigung) |
| `.github/workflows/android.yml` | Baut die Android-App (APK) automatisch auf GitHub |

## Web (GitHub Pages)
Alle Dateien ins Repository legen, GitHub Pages aktivieren, `…/pdf-werkstatt.html` öffnen und installieren.

## Android-App bauen
1. Repository auf GitHub hochladen (Branch `main`).
2. Tab **Actions** → „Android-App bauen“ → läuft automatisch bei jedem Push (oder „Run workflow“).
3. Nach ca. 5 Minuten unter dem Lauf das Artefakt **pdf-werkstatt-apk** herunterladen, entpacken, `app-release.apk` aufs Handy kopieren und installieren („Installation aus unbekannten Quellen“ für den Dateimanager erlauben).
4. Ein PDF antippen → „Öffnen mit“ → **PDF Werkstatt** → **Immer**. Oder: Einstellungen → Apps → Standard-Apps.

### Eigener Signaturschlüssel (empfohlen, damit Updates sich installieren lassen)
Einmalig auf dem PC (Java nötig):
```
keytool -genkeypair -v -keystore release.jks -alias pdfwerkstatt -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks > release.b64        (Windows: certutil -encode release.jks release.b64)
```
Dann im Repository unter Settings → Secrets and variables → Actions anlegen:
`KEYSTORE_BASE64` (Inhalt von release.b64), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (= pdfwerkstatt), `KEY_PASSWORD`.
Ohne diese Secrets wird mit einem wechselnden Testschlüssel signiert – dann muss man die App vor jedem Update deinstallieren.

## Sicherheit
- Content-Security-Policy mit Hashes: Skripte nur aus der Datei selbst, keine Verbindungen zu fremden Servern.
- Service Worker blockiert jede Anfrage an fremde Herkünfte.
- Android: keine INTERNET-Berechtigung (wird im Build geprüft), Safe Browsing und WebView-Statistik aus, keine Cloud-Sicherung.
- JavaScript in PDFs wird nie ausgeführt, Links in PDFs nicht geöffnet.
