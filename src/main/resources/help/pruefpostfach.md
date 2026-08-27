# Prüfpostfach (IMAP) einrichten

Über das Prüfpostfach weist WebTestHelper nach, ob über Kontaktformulare versendete Nachrichten tatsächlich im Posteingang eintreffen. Bei Prüfläufen mit Zustellnachweis wird die hinterlegte Prüfadresse in das E-Mail-Feld des Kontaktformulars eingetragen und das Formular abgeschickt. Das CMS oder Mailformular des Kunden muss so konfiguriert sein, dass eine Kopie (in der Regel als Blindkopie / BCC) an diese Prüfadresse geschickt wird.

## Die Einstellungen im Überblick

* **Server (Host):** Die Netzwerkadresse Ihres eingehenden IMAP-Servers (z. B. `imap.example.com` oder eine IP-Adresse).
* **Port:** Die Portnummer des IMAP-Servers (üblicherweise Port `993` für SSL/TLS oder `143` für STARTTLS).
* **Verschlüsselung (TLS):** Wählen Sie zwischen *STARTTLS*, *SSL/TLS* oder *Keine Verschlüsselung*. Für sichere Übertragung sollte immer eine verschlüsselte Verbindung gewählt werden.
* **Benutzername und Passwort:** Die Zugangsdaten zur Authentifizierung am IMAP-Server. Das Passwort wird im System verschlüsselt gespeichert.
* **Ordner:** Der IMAP-Ordner, in dem eingehende Nachrichten geprüft werden (Standard: `INBOX`).
* **Prüfadresse:** Die E-Mail-Adresse dieses Postfachs. Diese Adresse trägt WebTestHelper beim Absenden eines Formulars als Absender-E-Mail ein, damit Bestätigungs- oder Antwortnachrichten hier empfangen werden können.

## Postfach prüfen

Mit der Schaltfläche „Postfach prüfen“ testen Sie sofort, ob WebTestHelper eine Verbindung zum IMAP-Server herstellen, sich mit den Zugangsdaten anmelden und den gewünschten Ordner öffnen kann. Bei erfolgreicher Verbindung wird die aktuelle Anzahl der im Ordner vorhandenen Nachrichten angezeigt.
