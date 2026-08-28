# Benutzerabläufe (Journeys)

Benutzerabläufe ermöglichen es WebTestHelper, zusammenhängende Interaktionspfade auf einer Website schrittweise und automatisiert nachzustellen — beispielsweise Anmeldevorgänge, mehrstufige Formulare oder Kaufprozesse im Shop.

## Mehrstufige Aktionen

Ein Ablauf besteht aus einer geordneten Folge von Einzelschritten. Für jeden Schritt wird eine bestimmte Browser-Aktion ausgeführt:

* **`GOTO` (Aufrufen):** Navigiert zu einer angegebenen Webadresse.
* **`CLICK` (Klicken):** Klickt ein interaktives Element (Schaltfläche, Link) auf der Seite an.
* **`FILL` (Ausfüllen):** Trägt Text oder Zugangsdaten in ein Eingabefeld ein.
* **`SELECT` (Auswählen):** Wählt einen Eintrag aus einem Dropdown-Menü aus.
* **`PRESS` (Taste drücken):** Sendet einen Tastaturbefehl (z. B. `Enter`).
* **`HOVER` (Überfahren):** Bewegt den Mauszeiger über ein Element, um Menüs oder Tooltips einzublenden.
* **`WAIT_FOR` (Warten):** Wartet darauf, dass ein bestimmtes Element sichtbar wird.
* **`ASSERT` (Prüfen):** Überprüft einen Soll-Zustand, etwa ob ein bestimmter Text sichtbar ist oder die Seite eine erwartete URL erreicht hat.

Schritte können als **optional** gekennzeichnet werden: Kann ein optionales Element nicht gefunden werden (z. B. ein temporärer Hinweis), wird der Schritt ohne Fehler übersprungen.

## Zugangsdaten in Abläufen verwenden

Für Anmeldemasken und geschützte Bereiche können in den Schritten Platzhalter für hinterlegte Zugangsdaten eingetragen werden:

* `{{cred.NAME.username}}` für den Benutzernamen (z. B. `{{cred.login.username}}`)
* `{{cred.NAME.password}}` für das Passwort (z. B. `{{cred.login.password}}`)

Bei der Ausführung des Ablaufs werden die Werte automatisch aus dem verschlüsselten Speicher aufgelöst. In der Weboberfläche, in Berichten und in Protokollen werden Passwörter niemals im Klartext angezeigt.

## Robuste Selektoren und Selektor-Drift (Selector Drift)

Websites ändern sich fortlaufend: CSS-Klassen werden umbenannt, Element-IDs angepasst oder DOM-Strukturen überarbeitet. Damit Prüfabläufe bei solchen Änderungen nicht sofort fehlschlagen, speichert WebTestHelper für jedes Element eine ranggeordnete Liste mehrerer Erkennungsmuster (**Locator Candidates**):

1. **`TEST_ID`:** Eindeutige Test-Attribute (z. B. `data-testid`).
2. **`ROLE`:** Barrierefreie ARIA-Rollen (z. B. `button`, `link`).
3. **`LABEL`:** Zugehörige Feldbeschriftungen (`<label>`).
4. **`ID`:** HTML-ID des Elements.
5. **`TEXT`:** Sichtbarer Textinhalt des Elements.
6. **`CSS`:** CSS-Selektor als Ausweichoption.

### Die Ausweichleiter (Fallback-Ladder)

Beim Wiedergeben eines Schritts versucht WebTestHelper zuerst den bevorzugten Selektor (Rang 0). Ist das Element damit nicht mehr auffindbar, probiert das System automatisch die nachfolgenden Kandidaten der Reihe nach aus.

### Was Selektor-Drift bedeutet

Findet erst ein nachrangiger Selektor das Element, spricht man von **Selektor-Drift**: Der Ablauf läuft erfolgreich durch und bricht nicht ab, aber das System registriert die Abweichung. So werden Sie frühzeitig darauf aufmerksam gemacht, dass sich die Struktur der Website geändert hat — noch bevor die Prüfung bricht.

## Feststellungen im Prüflauf

Werden Benutzerabläufe während eines automatischen oder manuellen Prüflaufs ausgeführt, können zwei Arten von Feststellungen entstehen:

* **Fehlgeschlagener Schritt:** Ein Schritt konnte nicht erfolgreich abgeschlossen werden — etwa weil ein erforderliches Element nicht auffindbar war, eine Aktion fehlschlug oder eine Soll-Prüfung (Assertion) nicht erfüllt wurde. Der Prüflauf bricht den Ablauf an dieser Stelle ab, erfasst die genaue Fehlerursache sowie Screenshots zur Nachvollziehbarkeit und meldet eine Feststellung mit Schweregrad Fehler.
* **Selektor-Drift:** Der Schritt war erfolgreich, das Element wurde jedoch erst über einen Ausweich-Selektor gefunden. Dies wird als Warnung gemeldet, damit die Selektoren des Ablaufs rechtzeitig aktualisiert werden können, bevor weitere Änderungen der Website zum Ausfall führen.

Beide Befunde können wie alle anderen Feststellungen bewertet (triagiert), zur Kenntnis genommen oder bei Bedarf stummgeschaltet werden.
