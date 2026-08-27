# Schaltflächen und interaktive Bedienelemente

Die Prüfung der Schaltflächen kontrolliert, ob interaktive Elemente auf einer Seite beim Anklicken überhaupt eine beobachtbare Reaktion auslösen — beispielsweise das Öffnen eines Ausklappbereichs (Akkordeon), das Aufblenden eines Hinweisfensters, eine Seitennavigation oder eine sonstige sichtbare Veränderung im Aufbau der Seite.

## Was die Prüfung prüft

WebTestHelper untersucht sichtbare Schaltflächen und Verweise auf den Schlüsselseiten bzw. der Startseite. Für jede Schaltfläche wird geprüft:

1. **Seitennavigation:** Ändert sich die Webadresse der Seite (z. B. Wechsel zu einer Unterseite oder einem neuen Bereich)?
2. **Sichtbare DOM-Veränderung:** Ändert sich das HTML-Dokument sichtbar (z. B. Einblenden eines Akkordeons, Öffnen eines `<dialog>`-Elements, Änderung von Texten oder Klassen)?
3. **Hinweisfenster:** Erscheint ein Browser-Dialogfenster (Alert, Confirm)?
4. **Popup-Fenster:** Öffnet sich ein neues Fenster oder Tab?

Erfolgt nach dem Klick keinerlei dieser Reaktionen, meldet die Prüfung eine Warnung (`WARN`).

## Was die Prüfung niemals anklickt (Sicherheitsregeln)

Um ungewollte Seiteneffekte, Datenverluste oder Kosten auf echten Websites sicher auszuschließen, werden potenziell gefährliche Bedienelemente grundsätzlich nicht angeklickt:

* **Formulare und Absende-Knöpfe:** Alle Elemente innerhalb von Formularen (`<form>`) sowie Knöpfe vom Typ `submit` oder `reset` bleiben unberührt.
* **Gefährliche Aktionen:** Schaltflächen mit Beschriftungen wie „Löschen“, „Entfernen“, „Kündigen“, „Bestellen“, „Kaufen“, „Bezahlen“, „Abmelden“ oder „Download“ werden niemals ausgelöst.
* **Cookie-Banner und Zustimmungen:** Zustimmungs- und Ablehn-Schaltflächen von Consent-Bannern werden von dieser Prüfung ignoriert (hierfür gibt es die separate Cookie-Hinweis-Prüfung).
* **Externe Verweise und neue Tabs:** Links, die auf andere Domains verweisen oder in einem neuen Tab (`target="_blank"`) öffnen, werden nicht angeklickt.
* **Deaktivierte oder unsichtbare Elemente:** Ausgeblendete oder deaktivierte Knöpfe werden übersprungen.

## Triage: Warum manche „toten“ Schaltflächen harmlos sind

Wenn die Prüfung eine Warnung für eine Schaltfläche ausgibt, sollten Sie folgendes beachten:

* **Dekorative Schaltflächen:** Gelegentlich werden Knöpfe rein als Gestaltungselement oder Platzhalter eingesetzt, ohne dass eine Funktion dahinter liegen soll. Solche Befunde können nach kurzer Prüfung stummgeschaltet oder zur Kenntnis genommen werden.
* **Fehlendes oder blockiertes JavaScript:** Wenn ein Klick wirkungslos bleibt, fehlt oft die Verknüpfung zu einer JavaScript-Funktion, oder ein vorgeschalteter Skriptfehler hat die Ausführung verhindert.
* **Leere Ankerverweise (`href="#"`):** Ein Link mit Ziel `#` ohne hinterlegten Klick-Handler führt ins Leere und bietet Besuchern keinen Mehrwert.
