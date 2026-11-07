# Radar Spółek (Android)

Aplikacja na Androida (Kotlin + Jetpack Compose), która raz w tygodniu (w sobotę ok. 9:00) albo na żądanie
skanuje spółki z indeksu S&P 500 i pokazuje do 10 takich, które spełniają wszystkie cztery kryteria.
Dla każdej spółki można otworzyć dzienny wykres świecowy z ostatnich 6 miesięcy i sprawdzić wskaźnik P/E.

> To narzędzie filtruje spółki według reguł, które sam ustalasz. Nie jest poradą inwestycyjną.

## Jak zbudować

**Android Studio (najprościej):** File → Open → wskaż katalog `RadarSpolek`, poczekaj na synchronizację
Gradle i kliknij Run. Wymagania: Android Studio Ladybug lub nowsze, JDK 17, Android 8.0+ (API 26) na telefonie.

**Linia poleceń:** `./gradlew assembleDebug` (plik APK: `app/build/outputs/apk/debug/app-debug.apk`).

**Bez Android Studio:** wrzuć projekt do repozytorium na GitHubie. Workflow `.github/workflows/radar-spolek.yml` (w katalogu głównym repozytorium)
uruchomi testy i zbuduje APK. Gotowy plik pobierzesz z zakładki Actions → ostatni przebieg → Artifacts.
Na telefonie trzeba zezwolić na instalację z nieznanych źródeł.

**Podpis:** po pushu na `master` workflow buduje wersję release podpisaną kluczem z sekretów repozytorium
(`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, te same co
dla `invoice-scanner`) i publikuje ją w zakładce Releases. Własny numer wersji nadasz tagiem `radar-spolek-v1.2.3`.

## Jak dokładnie liczone są kryteria

Kryteria opisane słowami zamieniłem na konkretne reguły. Każdy próg można zmienić w Ustawieniach.

| # | Kryterium | Reguła w aplikacji (domyślne progi) |
|---|-----------|-------------------------------------|
| 1 | Branża w trendzie wzrostowym od 10 dni | Grupa = podbranża GICS (albo sektor; do wyboru w ustawieniach), co najmniej 3 spółki. W **każdej** z 10 ostatnich sesji ≥ 50% spółek z grupy zamknęło się wyżej niż dzień wcześniej. |
| 2 | Spółka w jednoznacznym trendzie wzrostowym od pół roku | Liczone na ostatnich 126 sesjach. Wszystkie trzy warunki naraz: (a) regresja liniowa ln(ceny) ma dodatnie nachylenie i R² ≥ 0,70, czyli wzrost jest regularny, a nie jednorazowym skokiem; (b) wzrost ceny ≥ 10%; (c) wyższe szczyty i wyższe dołki: maksimum i minimum zamknięć z drugiej połowy okresu (ostatnie 3 mies.) są wyższe niż z pierwszej. |
| 3 | P/E nie wyższe niż mediana branży | P/E TTM spółki ≤ mediana (× mnożnik, domyślnie 100%) dodatnich P/E spółek z tej samej grupy. Spółki ze stratą (ujemne lub brakujące P/E) są pomijane. |
| 4 | Odbicie po korekcie do poziomu ostatniego wyraźnego szczytu z ostatniego miesiąca | Szczyt P z ostatnich 22 sesji: najwyższy high w oknie ±3 sesji, po którym cena spadła o ≥ 3% (dzięki temu jest „wyraźny”). Następnie zamknięcie powyżej P (wybicie) i nowy szczyt T. Potem korekta od T o ≥ 2%, której dołek wypada w strefie P ± 2,5%, a żadne zamknięcie nie spada poniżej tej strefy (poziom się obronił). Na koniec odbicie: ostatnie zamknięcie ≥ dołek + 1,5% i wyższe niż zamknięcie z dnia dołka, przy czym dołek był najwyżej 7 sesji temu. |

## Gdy żadna spółka nie spełnia kryteriów

Aplikacja pokazuje wtedy kartę dla każdego kryterium, a na niej:
- ile spółek spełnia to kryterium niezależnie od pozostałych,
- ile spółek spełnia trzy pozostałe kryteria i odpada tylko na tym,
- dla każdego progu: obecną wartość i wartość, przy której pojawiłaby się co najmniej jedna rekomendacja,
  jeśli zmienisz tylko ten jeden parametr (z przykładową spółką i przyciskiem „Ustaw”). Gdy sama zmiana progu nie wystarczy,
  a kryterium nie spełnia żadna spółka, podana jest wartość, przy której spełniłaby je choć jedna spółka.

Progi ciągłe (odsetek rosnących, R², wzrost, P/E) liczone są dokładnie. Progi formacji odbicia
wyznacza przeszukanie wartości co 0,1 punktu procentowego lub co 1 sesję.

Ranking (gdy kandydatów jest więcej niż 10): R² × ln(1 + wzrost w 6 mies.), z premią za świeższe odbicie.

Jeśli sesja w USA jeszcze trwa, dzisiejsza niedomknięta świeca jest pomijana.

## Źródła danych

- **Skład S&P 500 i klasyfikacja GICS:** plik CSV z repozytorium `datasets/s-and-p-500-companies` na GitHubie
  (pobierany raz w tygodniu i zapisywany w telefonie).
- **Notowania dzienne:** Yahoo Finance, endpoint `/v8/finance/chart` (bez klucza). Około 500 zapytań na skan,
  przez 6 godzin trzymane w pamięci podręcznej.
- **P/E:** Yahoo Finance, endpoint `/v7/finance/quote` (wymaga ciasteczka i tokenu, który aplikacja pobiera sama).
  P/E jest pobierane tylko dla branż, które przeszły kryterium 1.
  Zapasowo możesz podać darmowy klucz **Financial Modeling Prep** w ustawieniach.

⚠ API Yahoo jest nieoficjalne i może zmienić się bez ostrzeżenia. Dostęp do tokenu bywa też blokowany
w UE przez ekran zgody RODO. Jeśli P/E się nie pobiera, wpisz klucz FMP. Dostęp do danych jest wydzielony
za interfejsem `MarketData` (`domain/ScreenerEngine.kt`), więc źródło da się podmienić, np. na płatne API.

## Struktura

```
app/src/main/java/pl/radar/spolek/
├── domain/          czysta logika (bez Androida), pokryta testami
│   ├── Models.kt          modele + ScreenerConfig (progi kryteriów)
│   ├── Analysis.kt        regresja, trend, wykrywanie odbicia, szerokość rynku, mediana
│   └── ScreenerEngine.kt  orkiestracja skanu
├── data/            sieć i pamięć podręczna (Yahoo, FMP, CSV S&P 500, ustawienia, wyniki)
├── work/            WorkManager: skan cotygodniowy i na żądanie + powiadomienia
└── ui/              Compose: lista rekomendacji, szczegóły z wykresem świecowym, ustawienia
app/src/test/…       testy jednostkowe algorytmów (./gradlew testDebugUnitTest)
```

## Możliwe rozszerzenia

- Szersze uniwersum (S&P 400/600, Russell 1000): wystarczy podmienić `Repository.universe()`.
- Historia skanów i śledzenie wyników rekomendacji w czasie.
- Filtr płynności (minimalny średni wolumen obrotu).
