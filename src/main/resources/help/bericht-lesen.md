# Berichte lesen und verstehen

Ein Prüfbericht fasst die Ergebnisse eines Prüflaufs zusammen und ordnet festgestellte Mängel in fünf klare Abschnitte ein: Neu, Wieder aufgetreten (Regression), Behoben, Unverändert offen und Bekannt. So sehen Sie auf einen Blick, was sich seit dem letzten Lauf verändert hat.

## Die fünf Berichtsabschnitte

Die Einteilung der Feststellungen folgt dem Differenzmodell des WebTestHelpers:

* **Neu aufgetreten:** Mängel, die in diesem Prüflauf zum ersten Mal auf der Website entdeckt wurden. Hier lohnt sich die zeitnahe Prüfung, ob es sich um kürzliche Änderungen an der Website handelt.
* **Wieder aufgetreten (Regression):** Mängel, die in einem früheren Lauf bereits als behoben galten, jetzt aber erneut aufgetreten sind. Diese verdienen besondere Aufmerksamkeit, da eine frühere Fehlerbehebung offenbar rückgängig gemacht wurde.
* **Behoben:** Mängel, die im vorherigen Zustand noch aktiv waren, bei diesem Prüflauf aber nicht mehr festgestellt werden konnten.
* **Unverändert offen:** Bereits bekannte, noch ungeprüfte Mängel aus früheren Läufen, die weiterhin bestehen.
* **Bekannt:** Feststellungen, die manuell zur Kenntnis genommen, stummgeschaltet oder als „wird nicht behoben“ eingestuft wurden (beispielsweise über die Übernahme eines Ausgangsbestands).

## Abdeckung und Teilprüfung

Ein Prüflauf erfasst genau die Seiten, die innerhalb der konfigurierten Grenzen (Seitenlimit, maximale Tiefe oder Zeitlimit) tatsächlich besucht wurden.

Erreicht ein Lauf sein Limit, schließt er mit einer **Teilabdeckung (Partial Coverage)** ab. Feststellungen auf nicht besuchten Seiten werden dabei **nicht** als behoben markiert. Dadurch wird verhindert, dass eine kurze Prüfung irrtümlich Probleme als behoben meldet, nur weil die betreffenden Seiten im aktuellen Lauf nicht an der Reihe waren.
