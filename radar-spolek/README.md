# Radar Spółek (Android)

Aplikacja na Androida (Kotlin + Jetpack Compose), która raz w tygodniu (w sobotę ok. 9:00) albo na żądanie
skanuje wszystkie akcje notowane w USA (NYSE, Nasdaq, NYSE American; opcjonalnie tylko S&P 500) i pokazuje do 10 takich, które spełniają wszystkie cztery kryteria.
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
| 1 | Branża w trendzie wzrostowym od 10 dni | Grupa = branża według Nasdaq (dla trybu S&P 500: podbranża GICS) albo sektor – do wyboru w ustawieniach; co najmniej 3 spółki. W **każdej** z 10 ostatnich sesji ≥ 50% spółek z grupy zamknęło się wyżej niż dzień wcześniej. |
| 2 | Spółka w jednoznacznym trendzie wzrostowym od pół roku | Liczone na ostatnich 126 sesjach. Wszystkie trzy warunki naraz: (a) regresja liniowa ln(ceny) ma dodatnie nachylenie i R² ≥ 0,70, czyli wzrost jest regularny, a nie jednorazowym skokiem; (b) wzrost ceny ≥ 10%; (c) wyższe szczyty i wyższe dołki: maksimum i minimum zamknięć z drugiej połowy okresu (ostatnie 3 mies.) są wyższe niż z pierwszej. |
| 3 | P/E nie wyższe niż mediana branży | P/E TTM spółki ≤ mediana (× mnożnik, domyślnie 100%) dodatnich P/E spółek z tej samej grupy. Spółki ze stratą (ujemne lub brakujące P/E) są pomijane. |
| 4 | Odbicie po korekcie do poziomu ostatniego wyraźnego szczytu z ostatniego miesiąca | Szczyt P z ostatnich 22 sesji: najwyższy high w oknie ±3 sesji, po którym cena spadła o ≥ 3% (dzięki temu jest „wyraźny”). Następnie zamknięcie powyżej P (wybicie) i nowy szczyt T. Potem korekta od T o ≥ 2%, której dołek wypada w strefie P ± 2,5%, a żadne zamknięcie nie spada poniżej tej strefy (poziom się obronił). Na koniec odbicie: ostatnie zamknięcie ≥ dołek + 1,5% i wyższe niż zamknięcie z dnia dołka, przy czym dołek był najwyżej 7 sesji temu. |

## Indeksy na ekranie głównym

Na górze ekranu głównego jest wykres **S&P 500** (`^GSPC`) i **Russell 2000** (`^RUT`), z przełącznikiem indeksu
i zakresem 1M / 3M / 6M / 1R. Karta pokazuje ostatnią wartość, zmianę dzienną i zmianę w wybranym zakresie, a także
średnią 50-sesyjną (linia przerywana). Kolor linii zależy od tego, czy indeks w zakresie wzrósł (zielony), czy spadł
(czerwony). Po dotknięciu lub przeciągnięciu po wykresie widać wartość wybranego dnia. Notowania pochodzą z Yahoo
Finance, ładują się przy otwarciu aplikacji niezależnie od skanu i są trzymane w pamięci podręcznej przez 15 minut.

## Trendomierz

Trendomierz pokazuje odsetek spółek rosnących. Dla każdej sesji z ostatnich 2 miesięcy aplikacja liczy, jaki procent
skanowanych spółek z notowaniem tego dnia zamknął się wyżej niż na poprzedniej sesji. Spółki bez zmian liczą się do
mianownika. Słupek (oś 0–100%, linia przerywana na 50%) jest **zielony**, gdy wzrosło co najmniej 50% spółek, i **czerwony**,
gdy mniej niż 50%. Ta sama reguła decyduje o kolorze lampek i liczbie „zielonych sesji” przy sortowaniu branż.
„Lampka” u góry karty pokazuje kolor ostatniej sesji. Po dotknięciu słupka widać szczegóły dnia: odsetek rosnących,
liczbę wzrostów, spadków i spółek bez zmian. Dane pochodzą z zamknięć pobieranych w trakcie skanu, więc nie wymagają
dodatkowych zapytań.

**Drill-down:** po dotknięciu karty (lub „Trendomierze branż ›”) otwiera się lista trendomierzy dla każdej branży,
a po przełączeniu – dla każdego sektora. Domyślnie lista jest posortowana według liczby zielonych sesji w ostatnich
2 tygodniach (10 sesji). Każda grupa ma lampkę ostatniej sesji, licznik „zielone 2 tyg.”, liczbę spółek w trendzie
i miniaturę wykresu z 2 miesięcy (ostatnie 2 tygodnie podświetlone).

**Branża:** dotknięcie grupy otwiera jej trendomierz i listę spółek w wyraźnym trendzie wzrostowym od pół roku
(kryterium 2 z ustawień). Spółki są uszeregowane według łącznej oceny 8 wskaźników fundamentalnych z Yahoo Finance:
P/E, Fwd P/E, PEG, P/B, D/E (im niżej, tym lepiej; wartości ujemne liczą się jako najgorsze) oraz ROE, marża netto
i wzrost przychodów r/r (im wyżej, tym lepiej). Ocena 0–100 to średni percentyl wskaźników na tle pozostałych spółek
z listy. Do jej policzenia potrzebne są co najmniej 3 wskaźniki. Przy każdej spółce widać wartości wszystkich
wskaźników, pokolorowane według pozycji w grupie. Wskaźniki są pobierane na żądanie i trzymane w pamięci podręcznej przez 24 h.

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

## Które spółki są skanowane

Domyślnie cały rynek USA, czyli lista z wyszukiwarki akcji Nasdaq: ok. 7 tys. instrumentów, a po odfiltrowaniu
warrantów, jednostek SPAC, praw poboru, akcji uprzywilejowanych, obligacji i instrumentów bez klasyfikacji
branżowej (głównie fundusze) zostaje ok. 5–6 tys. spółek. W ustawieniach można:
- ograniczyć skan do S&P 500,
- ustawić minimalną kapitalizację i minimalną cenę akcji (domyślnie bez limitu),
- zostawić tylko spółki z siedzibą w USA (bez ADR-ów i innych spółek zagranicznych notowanych w USA).

## Źródła danych i przebieg skanu

1. **Lista spółek:** API wyszukiwarki Nasdaq (`api.nasdaq.com/api/screener/stocks`). Zapasowo używana jest jego codziennie
   aktualizowana kopia z repozytorium `rreichel3/US-Stock-Symbols` na GitHubie. Lista jest odświeżana co 3 dni.
   Tryb S&P 500 korzysta z pliku CSV `datasets/s-and-p-500-companies`.
2. **Zamknięcia z roku dla wszystkich spółek:** Yahoo Finance `/v8/finance/spark`, 20 spółek na zapytanie, czyli ok. 300 zapytań.
   Na ich podstawie liczone są kryteria 1 (branża) i 2 (trend). Jeśli zapytanie zbiorcze zawiedzie, aplikacja
   pobiera wykresy pojedynczo.
3. **Pełne świece z 6 miesięcy:** Yahoo `/v8/finance/chart`, tylko dla spółek z branż w trendzie lub w trendzie
   wzrostowym. Służą do kryterium 4 (odbicie).
4. **P/E:** Yahoo `/v7/finance/quote`, tylko dla branż, które są potrzebne. Zapasowo działa klucz Financial Modeling Prep.

Skan całego rynku trwa zwykle kilka minut i pobiera kilkanaście–kilkadziesiąt MB, więc najlepiej uruchamiać go przez Wi-Fi.
W trakcie aplikacja pokazuje powiadomienie, bo działa jako praca pierwszoplanowa.

⚠ API Yahoo i Nasdaq są nieoficjalne i mogą zmienić się bez ostrzeżenia. Dostęp do tokenu Yahoo bywa też
blokowany w UE przez ekran zgody RODO. Jeśli P/E się nie pobiera, wpisz klucz FMP. Dostęp do danych jest wydzielony
za interfejsem `MarketData` (`domain/ScreenerEngine.kt`), więc źródło da się podmienić, np. na płatne API.

## Struktura

```
app/src/main/java/pl/radar/spolek/
├── domain/          czysta logika (bez Androida), pokryta testami
│   ├── Models.kt          modele + ScreenerConfig (progi kryteriów)
│   ├── Analysis.kt        regresja, trend, wykrywanie odbicia, szerokość rynku, mediana
│   └── ScreenerEngine.kt  orkiestracja skanu
├── data/            sieć i pamięć podręczna (Nasdaq, Yahoo, FMP, CSV S&P 500, ustawienia, wyniki)
├── work/            WorkManager: skan cotygodniowy i na żądanie + powiadomienia
└── ui/              Compose: lista rekomendacji, szczegóły z wykresem świecowym, ustawienia
app/src/test/…       testy jednostkowe algorytmów (./gradlew testDebugUnitTest)
```

## Możliwe rozszerzenia

- Historia skanów i śledzenie wyników rekomendacji w czasie.
- Filtr płynności (minimalny średni wolumen obrotu).
