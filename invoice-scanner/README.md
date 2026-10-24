# Faktury z Gmaila – skaner faktur do opłacenia (Android)

Aplikacja na Androida, która przeszukuje skrzynkę Gmail, znajduje w niej faktury do zapłaty,
odczytuje je (PDF, skany, zdjęcia, treść maila) i pokazuje gotowe dane do przelewu.

## Co potrafi

- **Logowanie przez konto Google** (Google Identity Services, uprawnienie tylko do odczytu `gmail.readonly`).
- **Wyszukiwanie faktur** – wiadomości z ostatnich 90 dni zawierające m.in. „faktura”, „FV”,
  „invoice”, „do zapłaty”, „termin płatności”. Każda wiadomość analizowana jest raz, a kolejne
  skanowania sprawdzają tylko nowe wiadomości.
- **Odczyt faktur**:
  - PDF z warstwą tekstową → PdfBox-Android,
  - zeskanowany PDF (bez tekstu) → render stron przez `PdfRenderer` i OCR (ML Kit, działa offline),
  - załączone zdjęcia JPG/PNG → OCR,
  - faktura w treści maila (tekst/HTML).
- **Rozpoznawane dane**: odbiorca (sprzedawca), NIP, numer rachunku (IBAN/NRB z weryfikacją sumy
  kontrolnej), kwota do zapłaty i waluta, numer faktury, data wystawienia, termin płatności
  (także „14 dni” od daty wystawienia), oznaczenie MPP (podzielona płatność), faktury już opłacone.
- **Dane do przelewu**: kopiowanie pojedynczych pól lub całości, udostępnianie, **kod QR w standardzie
  Związku Banków Polskich** do zeskanowania w aplikacji banku, ręczna poprawa odczytanych danych.
- Lista „Do zapłaty” / „Opłacone”, suma do zapłaty, wyróżnienie faktur po terminie.

Wszystkie dane zostają na telefonie (lokalny plik JSON). Aplikacja niczego nie wysyła poza
zapytaniami do Gmail API.

## Konfiguracja Google Cloud (wymagana, jednorazowo)

Gmail API wymaga własnego projektu OAuth:

1. Wejdź na <https://console.cloud.google.com/>, utwórz projekt.
2. **APIs & Services → Library** → włącz **Gmail API**.
3. **APIs & Services → OAuth consent screen**: typ *External*, uzupełnij nazwę aplikacji i e-mail,
   dodaj zakres `https://www.googleapis.com/auth/gmail.readonly`, a w sekcji **Test users** dodaj swój
   adres Gmail. (W trybie testowym aplikacja działa dla dodanych użytkowników bez weryfikacji Google.)
4. **APIs & Services → Credentials → Create credentials → OAuth client ID** → typ **Android**:
   - Package name: `pl.mczark.invoicescanner`
   - SHA-1: odcisk klucza, którym podpisujesz aplikację. Dla wersji debug:
     ```bash
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
     ```
     albo `./gradlew signingReport`.

Nie trzeba nic wpisywać w kodzie – Google rozpoznaje aplikację po nazwie pakietu i SHA-1.

## Budowanie i uruchomienie

Wymagania: Android Studio (Ladybug lub nowsze) / JDK 17+, Android SDK 35.
Urządzenie lub emulator z Androidem 8.0+ i usługami Google Play.

```bash
cd invoice-scanner
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # instalacja na podłączonym telefonie
./gradlew test                   # testy parsera faktur i kodu QR
```

Możesz też otworzyć katalog `invoice-scanner` w Android Studio i uruchomić konfigurację `app`.

GitHub Actions (`.github/workflows/invoice-scanner.yml`) przy każdej zmianie w tym katalogu uruchamia
testy jednostkowe i buduje APK debug, dostępny do pobrania jako artefakt `invoice-scanner-debug-apk`.

## Struktura

```
app/src/main/java/pl/mczark/invoicescanner/
├── auth/GmailAuthorizer.kt        # token OAuth (Google Identity Services)
├── gmail/GmailApi.kt              # klient Gmail REST API (OkHttp + kotlinx.serialization)
├── extract/AttachmentTextExtractor.kt  # PDF → tekst (PdfBox), skany i zdjęcia → OCR (ML Kit)
├── parser/InvoiceParser.kt        # heurystyki wyciągające dane z tekstu faktury
├── parser/Iban.kt                 # walidacja i formatowanie IBAN/NRB
├── payment/TransferQrPayload.kt   # treść kodu QR do przelewu (rekomendacja ZBP)
├── data/InvoiceScanner.kt         # orkiestracja: wyszukiwanie → pobieranie → parsowanie
├── data/InvoiceRepository.kt      # lokalny zapis faktur
├── MainViewModel.kt
└── ui/                            # ekrany Jetpack Compose (lista, szczegóły przelewu)
```

## Ograniczenia

- Rozpoznawanie danych jest heurystyczne – nietypowe układy faktur mogą wymagać ręcznej poprawki
  (ikona ołówka na ekranie szczegółów). Zawsze sprawdź dane przed wykonaniem przelewu.
- Kod QR w formacie ZBP obsługuje tylko przelewy krajowe w PLN do 9 999,99 zł; dla innych faktur
  dostępne jest kopiowanie danych.
- Zaszyfrowane (chronione hasłem) PDF-y nie są odczytywane – dane będą pochodzić z treści maila.
- Przy MPP aplikacja nie wylicza kwoty VAT – trzeba ją przepisać z faktury.
