# Prüfungen konfigurieren

Für jede Website lässt sich festlegen, welche Prüfungen WebTestHelper bei einem Lauf ausführt und mit welchem Schweregrad ihre Befunde erscheinen. Die Einstellungen gelten für alle künftigen Läufe dieser Website — manuell angestoßene ebenso wie die geplanten. Bereits erstellte Befunde bleiben unverändert.

## Prüfung aktivieren oder deaktivieren

Jede Prüfung hat einen Haken **„Prüfung aktiviert“**. Ist er gesetzt, führt WebTestHelper die Prüfung bei jedem Lauf aus. Ist er entfernt, überspringt die Prüfung diese Website und erzeugt dort keine Befunde. Das ist nützlich, wenn eine Prüfung für eine bestimmte Website nicht passt — etwa eine Prüfung auf fremdsprachige Seiten bei einer rein deutschen Website.

Eine deaktivierte Prüfung geht nicht verloren: Der Haken lässt sich jederzeit wieder setzen, und ab dem nächsten Lauf läuft die Prüfung wieder mit.

## Schweregrad wählen

Über **„Schweregrad“** lässt sich pro Prüfung festlegen, wie dringend ihre Befunde behandelt werden:

- **Standard** — die Prüfung verwendet ihren eigenen, eingebauten Schweregrad. Für die meisten Prüfungen ist das die richtige Wahl.
- **Fehler** — Befunde dieser Prüfung zählen als Fehler und gelten als besonders dringend.
- **Warnung** — Befunde dieser Prüfung zählen als Warnung.
- **Hinweis** — Befunde dieser Prüfung zählen als bloßer Hinweis und rücken in den Hintergrund.

Das Überschreiben ist gedacht für Prüfungen, deren eingebauter Schweregrad zur eigenen Situation nicht passt. Beispiel: Eine Prüfung meldet von sich aus nur Hinweise, für das eigene Geschäft ist ein dort entdeckter Mangel aber gravierend — dann lohnt sich die Einstellung „Fehler“.

## Wer darf die Prüfungen anpassen?

Jede angemeldete Kollegin und jeder angemeldete Kollege darf die Konfiguration anpassen. Änderungen wirken ab dem nächsten Lauf.
