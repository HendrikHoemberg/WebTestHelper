# Zeitpläne und Schlüsselseiten

Für jede Website lässt sich festlegen, wann WebTestHelper sie von selbst prüft. Drei Umfänge stehen zur Wahl; zu jedem gehört ein Zeitplan, den Sie hier pro Tier sehen und anpassen können.

## Die drei Umfänge

- **Puls-Prüfung** — läuft täglich. Sie besucht nur eine festgelegte Auswahl wichtiger Seiten, nicht die ganze Website, und ist damit der schnelle, günstige Gesundheitscheck für jeden Tag.
- **Vollständige Prüfung** — läuft wöchentlich, jeweils am Sonntag. Sie besucht alle erreichbar erscheinenden Seiten der Website und ist der ausführliche Lauf.
- **Tiefenprüfung** — läuft monatlich, jeweils am ersten Tag des Monats. Sie verschickt beim Prüfen Formular-Testnachrichten an die Website. Sie ist erst ab Ausbaustufe 3 wirksam.

Im Normalfall genügt es, oben die **Uhrzeit** zu wählen. Weicht der Zeitplan von den üblichen Mustern ab, lässt sich hinter **Erweitert** ein eigener Fachbegriff (ein Cron-Ausdruck) samt Zeitzone eintragen. Für die täglichen und wöchentlichen Prüfungen zeigt das Formular eine klar verständliche Beschreibung; die technische Angabe erscheint erst in der Aufklappung.

## Warum eine feste Auswahl bei der Puls-Prüfung?

Die Puls-Prüfung besucht nicht die gesamte Website, sondern nur eine **Schlüsselseiten**-Auswahl. Die Auswertung der Feststellungen vergleicht die besuchten Seiten eines Laufs mit dem Ort eines Befunds. Würde die Auswahl bei jedem Lauf neu berechnet, könnte derselbe Befund zwischen zwei Läufen scheinbar bestätigt oder behoben wirken, ohne dass sich an der Website etwas geändert hat. Eine einmal festgehaltene Liste verhindert dieses Springen.

Die Auswahl entsteht aus dem ersten vollständigen Lauf: Berücksichtigt werden die Seiten mit den meisten unterschiedlichen Verweisen auf sie, immer zusammen mit der Startseite. Nur Seiten, die beim Besuch tatsächlich erreichbar waren und fehlerfrei geantwortet haben, kommen in die Liste. Ein Lauf, der sein Seiten- oder Zeitlimit erreicht, hinterlegt keine Liste. Die Schlüsselseiten lassen sich im Bearbeitungsformular einer Website selbst festlegen; beim nächsten vollständigen Lauf wird eine bereits vorhandene Auswahl nicht überschrieben.

## Was bewirkt das Anhalten der Planung?

Unter **Einstellungen** lässt sich die Planung global anhalten. Solange sie angehalten ist, startet kein Prüflauf von selbst — auf keiner Website. Ein Lauf über „Jetzt prüfen“ bleibt trotzdem möglich. Das ist gedacht für Wartungsfenster oder Umbauten, in denen die Website nicht angestoßen werden soll. Auch eine einzelne Website lässt sich über das Bearbeitungsformular deaktivieren; ebenso lässt sich pro Tier der Zeitplan abwählen.

## Warum ist die Tiefenprüfung monatlich und nicht täglich?

Die Tiefenprüfung ist bewusst der seltenste Lauf. Unter der Website und den internen Systemen dahinter liegen drei Gründe:

1. **Seitenwirkung:** Die Tiefenprüfung verschickt Formular-Testnachrichten und löst damit echte Aktionen in Systemen der Website aus. Solche Auswirkungen gehören nicht in einen täglichen Rhythmus.
2. **Last auf dem Firmen-Hosting:** Ein vollständiger Tiefenlauf besucht alle Seiten inklusive aller Vorstufen und erzeugt dabei spürbar mehr Anfragen als die Puls-Prüfung. Täglich würde das die Kapazität des eigenen Hostings unnötig belasten.
3. **Drittgrenzen:** Viele Fremddienste drosseln die Zahl der Anfragen von einer Quelle. Ein monatlicher Tiefenlauf bleibt unter diesen Grenzen, ein täglicher würde regelmäßig anstoßen.

Der monatliche Takt ist also ein Kompromiss aus aussagekräftigem Befund und vertretbarer Belastung — für die Website, für das eigene Hosting und für die Dienste der Drittanbieter.
