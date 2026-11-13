# Umgebungsscanner

Musterlösung zum Leistungsnachweis „Permissions und Systemzugriffe“ (Labor für App-Entwicklung, AI3, WiSe 2026/27).
Kotlin, Jetpack Compose, Material 3.

## Aufbau

| Bereich | Datei |
|---|---|
| Startbildschirm, Permission-Flows (Standort, Kamera) | `scanner/HomeScreen.kt`, `scanner/Permissions.kt` |
| Zustand des Scanners (StateFlow, übersteht Drehen) | `scanner/ScannerViewModel.kt` |
| Standort mit Rechte-Prüfung, GPS-Prüfung, Timeout, ohne `!!` | `data/LocationRepository.kt` |
| Balance-Challenge (Canvas, Gyroskop per `DisposableEffect`) | `challenge/ChallengeScreen.kt` |
| Spiellogik ohne Android (Gyroskop → Geschwindigkeit → Wind → Position → Timer) | `challenge/BalanceEngine.kt` |
| Individuelle Werte aus der Matrikelnummer | `challenge/ChallengeConfig.kt` |
| Matrikelnummer, Debug-Schalter, Bestzeit (DataStore) | `data/SettingsRepository.kt`, `settings/SettingsDialog.kt` |

## Teil A: Umgebungsscanner

- **Standort:** FINE und COARSE gemeinsam über `RequestMultiplePermissions`. Vor der Abfrage wird geprüft, ob die Standortdienste an sind (`LocationManagerCompat.isLocationEnabled`), danach `getCurrentLocation` mit 10 s Timeout (`withTimeoutOrNull`). Ohne Google Play Services greift ein Fallback auf den `LocationManager`.
- **Foto:** `TakePicturePreview`, vorgeschaltet die `CAMERA`-Permission. Das Foto liegt im ViewModel und übersteht das Drehen.
- **„Scan starten“:** erst Standort, danach Foto.

### Fehlerfälle und wie man sie auslöst

| Fall | Auslösen | Verhalten der App |
|---|---|---|
| Standortrecht verweigert | Standort tippen, im Dialog „Nicht zulassen“ | Meldung „Standortzugriff verweigert“ mit „Erneut fragen“ |
| Standort dauerhaft verweigert | zweimal ablehnen, erneut tippen | Meldung mit Button „Einstellungen öffnen“ |
| Nur ungefährer Standort | im Dialog „Ungefähr“ wählen | Standort wird angezeigt, Hinweis mit „Genauen Standort erlauben“ |
| GPS aus | Recht erteilen, Standort in den Schnelleinstellungen ausschalten | Meldung „Standort ist ausgeschaltet“ mit „Standort einschalten“ |
| Kein Standort | Emulator per Cold Boot ohne gesetzte Position starten | nach 10 s Meldung „Kein Standort gefunden“ statt endlosem Ladekreis |
| Kamerarecht verweigert | Kamera tippen, im Dialog ablehnen | Meldung mit „Erneut fragen“, bei dauerhaft „Einstellungen öffnen“ |
| Kamera abgebrochen | Kamera öffnen, mit Zurück abbrechen | Hinweis „Aufnahme abgebrochen“, altes Foto bleibt |
| Gerät gedreht | Foto aufnehmen, Emulator drehen | Foto und Standort bleiben erhalten (ViewModel) |
| Kein Gyroskop | Zahnrad → „Debug: Sensor fehlt“ einschalten, Sensor Challenge öffnen | Meldung „Kein Gyroskop gefunden“ statt Absturz |

Kommt man aus den App-Einstellungen zurück, prüft die App die Rechte erneut und entfernt veraltete Meldungen.

## Teil B: Balance-Challenge

- Gyroskop über `SensorManager`, An- und Abmelden mit `DisposableEffect`.
- `v += ω · k` je Messung (dt aus `SensorEvent.timestamp`), Reibung, zufällige Windstöße, `p += v`.
- Der Ball bleibt am Rand der Spielfläche hängen.
- Liegt der Ball im Zielkreis, läuft der Timer, verlässt er ihn, beginnt die Zeit von vorn. Ist die Haltezeit erreicht, erscheint „Geschafft!“.
- Während der Challenge ist der Bildschirm auf Hochformat fixiert, damit die Gyroskop-Achsen zur Anzeige passen.

### Individuelle Werte

In den Einstellungen (Zahnrad oben rechts) wird die Matrikelnummer eingetragen. Sie steht in der oberen Leiste und bestimmt:

| Ziffer | Wert |
|---|---|
| letzte | Haltezeit = 3 + (Ziffer mod 4) s |
| vorletzte | Zielradius = 40 + Ziffer · 5 dp |
| drittletzte | Windstufe = 1 + (Ziffer mod 3) |

Windstufen: Stufe 1 = Stöße bis ca. 18 dp/s alle 1,6–2,6 s, Stufe 2 = ca. 28 dp/s alle 1,1–1,9 s, Stufe 3 = ca. 38 dp/s alle 0,7–1,3 s.

### Testen im Emulator

Extended Controls (drei Punkte) → Virtual sensors → Device Pose → Modus „Rotate“, das 3D-Handy in kleinen Bewegungen ziehen. Das Gyroskop liefert nur Werte, solange sich das Gerät dreht.

## Bonus

- Zustand in ViewModels mit `StateFlow`, in der UI per `collectAsStateWithLifecycle()`.
- Bestzeit pro Matrikelnummer in DataStore, übersteht einen Neustart der App.

## Bauen

```bash
./gradlew assembleDebug testDebugUnitTest
```

Benötigt JDK 17 und das Android SDK (compileSdk 37). Screenshots für die Abgabe kommen in `screenshots/`.
