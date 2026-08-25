# E-Mail-Versand (SMTP) einrichten

Über die SMTP-Einstellungen verbinden Sie den WebTestHelper mit Ihrem E-Mail-Server, um Benachrichtigungen über neu aufgetretene oder wiederkehrende Fehler sowie regelmäßige Statusberichte automatisch zu versenden.

## Die fünf SMTP-Felder im Überblick

* **Server (Host):** Die Netzwerkadresse Ihres ausgehenden Mailservers (z. B. `mail.example.com` oder eine IP-Adresse).
* **Port:** Die Portnummer des Mailservers (üblicherweise Port `587` für STARTTLS, `465` für SSL/TLS oder `25` für unverschlüsselte Verbindungen).
* **Verschlüsselung (TLS):** Wählen Sie zwischen *STARTTLS*, *SSL/TLS* oder *Keine Verschlüsselung*. Für sichere Übertragung sollte immer eine verschlüsselte Verbindung gewählt werden.
* **Benutzername und Passwort:** Die Zugangsdaten zur Authentifizierung am Mailserver. Das Passwort wird im System verschlüsselt gespeichert.
* **Absenderadresse (From):** Die E-Mail-Adresse, die als Absender in generierten Benachrichtigungen eingetragen wird (z. B. `webtesthelper@example.com`).

## Test-E-Mail senden

Mit der Schaltfläche „Test-E-Mail senden“ prüfen Sie unmittelbar, ob die Verbindung zum Mailserver erfolgreich aufgebaut werden kann, die Zugangsdaten stimmen und E-Mails zugestellt werden. So stellen Sie sicher, dass wichtige Warnungen im Ernstfall nicht unbemerkt verloren gehen.

## Alle Nachrichten umleiten (Staging-Modus)

Die Option **Alle Nachrichten umleiten** erlaubt die Angabe einer festen Empfängeradresse für Test- oder Staging-Umgebungen. Ist diese Adresse gesetzt, werden ausnahmslos alle Benachrichtigungen dorthin geschickt – so wird zuverlässig verhindert, dass Testläufe versehentlich E-Mails an echte Website-Betreiber oder Kunden versenden.
