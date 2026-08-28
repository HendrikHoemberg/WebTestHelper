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

## Zuverlässigkeit und Ablauf-Gesundheit (Journey Health)

WebTestHelper erfasst für jeden Ablauf statistische Kennzahlen zur Zuverlässigkeit über mehrere Prüfläufe hinweg:

* **Letzter Erfolg:** Der Zeitpunkt, an dem der Ablauf zuletzt vollständig (oder mit Selektor-Drift) fehlerfrei durchgelaufen ist.
* **Fehlschläge in Folge:** Wie oft der Ablauf seit dem letzten erfolgreichen Durchlauf hintereinander gescheitert ist. Ein erfolgreicher Durchlauf setzt diesen Zähler sofort wieder auf 0 zurück.
* **Selektor-Abweichungen (Drift-Anzahl):** Die Gesamtzahl aller Schritte, bei denen auf einen nachrangigen Ausweich-Selektor zurückgegriffen werden musste.
* **Betroffene Schritte:** Welche Schritte beim *letzten* Prüflauf über einen Ausweich-Selektor gefunden wurden. Die Detailansicht markiert diese Schritte in der Schritt-Tabelle mit **„Abweichung“**. Anders als die Drift-Anzahl, die über alle Prüfläufe hinweg summiert wird, bezieht sich diese Markierung immer nur auf den jüngsten Durchlauf: Läuft ein Schritt wieder über sein bevorzugtes Erkennungsmuster, verschwindet seine Markierung beim nächsten Prüflauf. So ist erkennbar, an welcher Stelle des Ablaufs sich die Website verändert hat, ohne alle Schritte einzeln durchsehen zu müssen.

### Wann eine Neuaufzeichnung erforderlich ist

Tritt bei einem Ablauf wiederholt ein Fehler auf, nachdem bereits Selektor-Abweichungen verzeichnet wurden (mindestens 3 Fehlschläge in Folge und mindestens 1 Drift), markiert WebTestHelper den Ablauf in der Übersicht und der Detailansicht mit dem Hinweis **„Neuaufzeichnung erforderlich“**.

Dieser Status wird bewusst als Hinweiszustand in der Oberfläche und nicht als dauerhafte Feststellung dargestellt:
* Wiederholte Fehler *ohne* vorherigen Drift deuten darauf hin, dass die Website selbst gestört ist (z. B. ein Serverfehler oder ein fehlerhaftes Formular) — dies wird als reguläre Feststellung gemeldet.
* Wiederholte Fehler *nach* aufgetretenem Drift deuten darauf hin, dass die hinterlegten Selektoren und Interaktionsschritte nicht mehr zur veränderten Website passen. Eine dauerhafte Feststellung würde jede Nacht dieselbe Fehlermeldung im Bericht erzeugen, obwohl die Ursache in einer veralteten Testaufzeichnung liegt.

Sobald der Ablauf neu aufgezeichnet oder angepasst wurde und wieder erfolgreich durchläuft, wird der Hinweis automatisch aufgehoben.

## Interaktive Aufzeichnung (Live-Recorder)

Mit dem interaktiven Live-Recorder können Sie neue Benutzerabläufe direkt in Ihrer WebTestHelper-Oberfläche aufzeichnen:

* **Live-Ansicht (Screencast):** Der gesteuerte Chromium-Browser sendet in Echtzeit hochauflösende Bild-Frames an die Web-Oberfläche, die im Canvas-Bereich dargestellt werden.
* **Direkte Interaktion:** Klicks, Mausrad-Scrollen und Tastatureingaben auf der Leinwand werden an den gesteuerten Browser übertragen und dort verzögerungsfrei ausgeführt. Über das Scrollen erreichen Sie auch Elemente, die nicht im ersten Bildschirmbereich liegen.
* **Gleichzeitige Sitzungen (Kapazitätsgrenze):** Um die Systemleistung und Ressourcen für reguläre Prüfläufe stabil zu halten, sind maximal **2 Aufzeichnungssitzungen gleichzeitig** möglich. Ist diese Kapazitätsgrenze erreicht, weist das System darauf hin und fordert dazu auf, eine laufende Sitzung zu beenden oder es später erneut zu versuchen (§13.4).
* **Automatisches Timeout:** Jede Aufzeichnungssitzung verfügt über ein Leerlauf-Zeitlimit von **15 Minuten**. Erfolgen innerhalb dieses Zeitfensters keine Eingaben, wird die Sitzung automatisch beendet und der Browser-Worker freigegeben.
* **Browser lässt sich nicht starten:** Schlägt der Start des Aufnahme-Browsers fehl, ist das keine Kapazitätsfrage — Warten hilft dann nicht. Die Oberfläche weist gesondert darauf hin; bitte wenden Sie sich in diesem Fall an die Administration.
* **Aufzeichnung speichern:** Über das Eingabefeld und die Schaltfläche „Ablauf speichern“ wird die Aufzeichnung als neuer Ablauf übernommen. Die erfassten Interaktionsereignisse werden automatisch in eine bereinigte Schrittfolge überführt (inkl. Start-URL als erster Navigationsschritt). Anschließend gelangen Sie direkt in den Editor zur Feinabstimmung.
* **Aufzeichnung beenden:** Über die Schaltfläche „Aufzeichnung beenden“ oder beim Verlassen der Seite wird die Sitzung geschlossen, ohne die Aufzeichnung zu speichern, und der Browser-Worker wird sofort freigegeben.

## Ablauf-Editor (Schritte anpassen und verfeinern)

Im Ablauf-Editor können Sie aufgezeichnete oder bestehende Abläufe vor dem Produktiveinsatz prüfen und nachbearbeiten (§10.4):

* **Überflüssige Schritte löschen:** Versehentliche Klicks oder Zwischenschritte können mit einem Klick entfernt werden. Die verbleibenden Schritte werden automatisch lückenlos durchnummeriert; ihre eindeutigen IDs (**Schritt-UUIDs**) bleiben dabei unverändert erhalten.
* **Schritte umordnen:** Über die Pfeilschaltflächen können Schritte in der Ausführungsreihenfolge nach oben oder unten verschoben werden. Auch hierbei bleiben die Schritt-UUIDs stabil.
* **Eingabewerte und Zugangsdaten:** Formulardaten und URL-Ziele können direkt bearbeitet werden. Anstelle fester Passwörter können Sie hier Platzhalter für hinterlegte Zugangsdaten eintragen (z. B. `{{cred.login.password}}`). Der Editor zeigt den Platzhalter unverändert an und löst Passwörter im Formular niemals in Klartext auf.
* **Optionale Schritte:** Schritte können als optional markiert werden, damit fehlende optionale Elemente (wie Banner oder optionale Dialoge) den Ablauf nicht abbrechen.
* **Soll-Prüfungen (Assertions) hinzufügen:** Für jeden Schritt kann eine explizite Prüfung definiert werden:
  * **`TEXT_CONTAINS`:** Prüft, ob das Element den erwarteten Text enthält.
  * **`VISIBLE`:** Prüft, ob das Element sichtbar auf der Seite vorhanden ist.
  * **`URL_MATCHES`:** Prüft, ob die aktuelle Browser-URL mit dem angegebenen regulären Ausdruck übereinstimmt.
  * **`COUNT`:** Prüft, ob die genaue Anzahl passender Elemente auf der Seite vorhanden ist.
* **Zeitlimits (Timeout):** Das Zeitlimit pro Schritt kann individuell angepasst werden (Standard: 5000 ms).


