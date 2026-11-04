# Lite Browser – cum obții aplicația (APK)

## Varianta 1: GitHub (gratuit, fără să instalezi nimic pe PC) – recomandat

1. Fă-ți cont pe https://github.com (dacă nu ai).
2. Apasă **+** (dreapta sus) → **New repository** → nume `LiteBrowser` → **Create repository**.
3. Pe pagina nouă apasă **uploading an existing file**.
4. Dezarhivează `LiteBrowser.zip` pe PC, intră în folderul `LiteBrowser` și trage în pagină
   **tot conținutul** (folderele `app`, `.github` și fișierele de lângă ele) → **Commit changes**.
   - Dacă folderul `.github` nu apare (Windows îl ascunde uneori): în GitHub mergi la **Actions** →
     **set up a workflow yourself**, lipește conținutul fișierului `.github/workflows/build.yml` → **Commit**.
5. Mergi la tabul **Actions**. Pornește singur „Build APK” (durează ~3-5 minute).
6. Când are bifă verde, intră pe el → jos la **Artifacts** → descarcă **LiteBrowser-apk** (e un .zip cu APK-ul).

## Varianta 2: Android Studio

Deschide folderul `LiteBrowser` în Android Studio → **Build → Build APK(s)**.

## Instalarea pe telefon

Pune `app-release.apk` în folderul `platform-tools` și rulează în CMD:

```
adb install app-release.apk
```

(Sau copiază APK-ul pe telefon și deschide-l din „Fișierele mele”.)

## Ce face

- Pagina de start cu **Instagram**, **TikTok** și **Messenger**.
- **Login-urile rămân salvate** pe toate site-urile (cookie-uri permanente). „Golește cache” nu le șterge.
- Mai multe taburi: doar tabul vizibil rulează; peste limită (implicit 3) taburile „adorm”
  și nu mai ocupă RAM. Limita se schimbă din meniul ⋮.
- **Rulează cu ecranul stins** (meniul ⋮): paginile rămân active când închizi ecranul.
  Apare o notificare permanentă și consumă mai multă baterie.
- Când telefonul rămâne fără RAM, taburile din fundal sunt eliberate automat.
- Linkurile „Deschide în aplicație” rămân în browser (nu pornesc aplicațiile grele).
- Încărcare de poze, descărcări, video pe tot ecranul.
- Poate fi setat ca browser implicit.
