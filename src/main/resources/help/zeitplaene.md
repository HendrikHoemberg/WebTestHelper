# Schlüsselseiten und Zeitpläne

Für die regelmässige („Pulse“-)Prüfung besucht WebTestHelper nicht die gesamte Website, sondern nur eine festgelegte Auswahl wichtiger Seiten — die sogenannten **Schlüsselseiten**. Diese Auswahl wird beim ersten vollständigen Lauf einer Website automatisch festgehalten und danach nicht mehr verändert.

## Warum eine feste Auswahl?

Die Auswertung der Feststellungen vergleicht die besuchten Seiten eines Laufs mit dem Ort eines Befunds. Würde die Auswahl der Schlüsselseiten bei jedem Lauf neu berechnet, könnte derselbe Befund zwischen zwei Läufen scheinbar bestätigt oder behoben wirken, ohne dass sich an der Website etwas geändert hat. Eine einmal festgehaltene Liste verhindert dieses Springen.

## Wie wird die Liste befüllt?

Die Auswahl entsteht aus dem ersten vollständigen Lauf: Die Seiten mit den meisten unterschiedlichen Verweisen auf sie werden berücksichtigt, immer zusammen mit der Startseite. Nur Seiten, die beim Besuch tatsächlich erreichbar waren und fehlerfrei geantwortet haben, kommen in die Liste. Seiten, die beim ersten Lauf fehlschlugen oder gar nicht besucht wurden, werden nicht aufgenommen.

Ein Lauf, der sein Seiten- oder Zeitlimit erreicht, hinterlegt keine Liste — die Auswahl bliebe sonst eine zufällige Momentaufnahme.

## Was kann ich tun?

Die Schlüsselseiten lassen sich im Bearbeitungsformular einer Website selbst festlegen (eine Adresse pro Zeile). Beim nächsten vollständigen Lauf wird eine bereits vorhandene Auswahl nicht überschrieben.
