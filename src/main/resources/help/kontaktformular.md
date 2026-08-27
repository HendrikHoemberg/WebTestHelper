# Kontaktformulare und Zustellung

Die Kontaktformular-Prüfung untersucht, ob das Kontaktformular einer Website für Besucher ordnungsgemäß funktioniert. Sie füllt die Eingabefelder mit plausiblen Testdaten aus und stellt sicher, dass Pflichtfelder valide Eingaben annehmen und Fehleingaben erkannt werden. Je nach gewählter Betriebsart wird das Formular zusätzlich testweise abgesendet und der Empfang der Nachricht im Prüfpostfach nachgewiesen.

## Die drei Betriebsarten im Überblick

In den Website-Einstellungen kann für jede Website eine von drei Betriebsarten gewählt werden:

1. **Nur ausfüllen, nicht absenden (Standard):**
   Das Formular wird mit plausiblen Testwerten ausgefüllt und die Gültigkeit der Eingaben im Browser geprüft. Es wird **keine** Nachricht abgeschickt. Diese Betriebsart hat keinerlei Auswirkung auf den Kunden und erzeugt keine Testnachrichten in dessen Postfach. Sie ist standardmäßig aktiv.

2. **Ausfüllen und absenden:**
   Das Formular wird ausgefüllt und tatsächlich über die Website abgeschickt. Die Testnachricht ist im Betreff und Text ausdrücklich als automatische Prüfung von WebTestHelper gekennzeichnet und enthält eine eindeutige Prüfkennung. Sie landet im Postfach, das die Website für Anfragen nutzt.

3. **Absenden und Zustellung im Prüfpostfach nachweisen:**
   Wie „Ausfüllen und absenden“, jedoch prüft WebTestHelper zusätzlich über ein globales IMAP-Prüfpostfach, ob die Nachricht dort tatsächlich angekommen ist. Dies setzt voraus, dass das Formular eine Kopie (BCC) an die in den globalen Einstellungen hinterlegte Prüfadresse sendet.

## Warum der Standard nichts verschickt

Die Standardeinstellung *„Nur ausfüllen, nicht absenden“* garantiert, dass die Prüfung sofort nach der Einrichtung gefahrlos eingeschaltet werden kann, ohne Kunden mit unerwarteten Testmails zu überraschen oder CRM-Systeme mit Testdaten zu belasten. Trotzdem werden gravierende Fehler sofort aufgedeckt — beispielsweise Pflicht-Auswahlfelder (`<select>`), bei denen keine gültige Option ausgewählt werden kann.

## Warum das Absenden nur monatlich im Tiefenlauf erfolgt

Das Absenden von Testnachrichten über ein echtes Kundenformular ist eine weitreichende Aktion. Aus diesem Grund wird das Absenden ausschließlich im monatlichen **Tiefenlauf** (`DEEP`) ausgeführt:

* **Schutz des Kundenpostfachs:** Ein wöchentliches Absenden würde Kundenpostfächer und Ticketsysteme unnötig mit wiederkehrenden Testnachrichten füllen.
* **Wahrheitsgetreue Befundauflösung:** Eine Website im Absende-Modus wird bei regulären Läufen übersprungen (die Prüfung enthält sich). Dadurch wird verhindert, dass ein regulärer Lauf ohne Absenden eine zuvor festgestellte fehlende Zustellung fälschlicherweise als „behoben“ markiert.

## Schutz vor Spam-Fallen (Honeypots) und Sicherheitsregeln

WebTestHelper erkennt Spam-Fallen (Honeypot-Felder) anhand ihrer geometrischen und visuellen Eigenschaften (unsichtbare, ausgeblendete oder außerhalb des Sichtfelds platzierte Felder) und lässt diese grundsätzlich unberührt. Suchformulare, Newsletter-Anmeldungen, Login-Masken sowie mit Captcha geschützte Formulare werden niemals ausgefüllt oder abgeschickt.

## Typische Feststellungen und Abhilfe

* **Pflichtfeld nimmt keine gültige Eingabe an (`rejectsValid`):** Ein Pflichtfeld verhindert das Absenden, etwa weil in einem Dropdown-Menü keine auswählbaren Optionen hinterlegt sind. Prüfen Sie die Konfiguration der Formularfelder im Redaktionssystem.
* **Ungültige E-Mail-Adresse akzeptiert (`acceptsInvalid`):** Das Formular akzeptiert unvollständige Eingaben ohne E-Mail-Format. Ergänzen Sie im Formular den Typ `type="email"` oder eine serverseitige Validierung.
* **Versand nicht bestätigt (`noSuccess`) oder Fehlermeldung (`errorShown`):** Nach dem Absenden meldet die Seite einen Fehler oder zeigt keine Bestätigung. Prüfen Sie die Formularverarbeitung und Skripte auf dem Server.
* **Nachricht nicht zugestellt (`notDelivered`):** Die Nachricht wurde laut Website versendet, kam jedoch nicht im Prüfpostfach an. Prüfen Sie den E-Mail-Versand (Mailserver, SPF, DKIM) und die Empfängerkonfiguration des CMS.
