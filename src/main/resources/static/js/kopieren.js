// Kopier-Helfer für die Link-Zeile in Befund-Karten. Als Alpine-Data registriert, damit
// dieselbe Befund-Karte (Prüflauf, Feststellungen, Detailseite) ohne Duplikat funktioniert.
document.addEventListener("alpine:init", function () {
    Alpine.data("wthKopiere", () => ({
        kopiert: false,
        kopiere(event) {
            const trigger = event.currentTarget || (event.target && event.target.closest ? event.target.closest("[data-url]") : null);
            const url = trigger ? trigger.getAttribute("data-url") : null;
            if (!url) {
                return;
            }
            if (navigator.clipboard && window.isSecureContext) {
                navigator.clipboard.writeText(url)
                    .then(() => this.zeigeKopiert())
                    .catch(() => this.fallback(url));
            } else {
                this.fallback(url);
            }
        },
        fallback(url) {
            const textarea = document.createElement("textarea");
            textarea.value = url;
            textarea.setAttribute("readonly", "");
            textarea.style.position = "absolute";
            textarea.style.left = "-9999px";
            document.body.appendChild(textarea);
            textarea.select();
            try {
                document.execCommand("copy");
                this.zeigeKopiert();
            } catch (e) {
                // Bleibt still, wenn der Browser das Kopieren verweigert.
            }
            document.body.removeChild(textarea);
        },
        zeigeKopiert() {
            this.kopiert = true;
            setTimeout(() => {
                this.kopiert = false;
            }, 1800);
        }
    }));
});
