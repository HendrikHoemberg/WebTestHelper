# Einrichtung einer Website

Nach dem Anlegen einer Website untersucht WebTestHelper sie einmal kurz und schlägt daraufhin eine Prüfkonfiguration vor. Sie bestätigen den Vorschlag und bearbeiten die Website nicht — Sie übernehmen.

## Was die Erstuntersuchung tut

Die Erstuntersuchung besucht die Startseite und einige weitere Seiten der neuen Website. Sie erkennt unter anderem Formulare, Videos und Tonspuren, Karten-Einbettungen, mehrere Sprachfassungen, Dokumente zum Herunterladen, ein Inhaltsverzeichnis (sitemap.xml) und die Verschlüsselung der Website. Für jede erkannte Eigenschaft findet sie die passende Prüfung und schlägt sie vor, mit einem Grund.

## Was die Erstuntersuchung ausdrücklich nicht tut

- **Sie füllt keine Formulare aus und sendet nichts ab.** Sie liest nur, was eine Seite enthält. Ein gefundenes Kontaktformular wird nur als Information angezeigt; Formular-Prüfungen sind eine spätere Ausbaustufe.
- **Sie verändert die Schlüsselseiten nicht.** Die Auswahl für die Puls-Prüfung entsteht weiterhin aus einem vollständigen Lauf, nicht aus der Erstuntersuchung.
- **Sie besucht höchstens acht Seiten.** Die Untersuchung bleibt ein schneller Überblick und bewegt sich innerhalb eines kurzen Zeitbudgets.

## Nach einem Fehlschlag

Schlägt die Erstuntersuchung fehl (etwa wenn die Website kurz nicht erreichbar war), können Sie die Untersuchung erneut anstoßen. Der Knopf **Übernehmen** bleibt auch dann vorhanden, damit Sie die Website trotzdem weiter einrichten können.
