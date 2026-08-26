package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import java.util.List;

/**
 * The guided-setup confirmation form: a multi-select of {@link CheckType}s. Only the
 * {@code aktiv} set survives a submit; the controller makes that set authoritative for the
 * whole catalog, so a check the {@code SiteService} seeded on is disabled here unless ticked.
 */
public class SetupForm {

    private List<CheckType> aktiv = List.of();

    public List<CheckType> getAktiv() {
        return aktiv;
    }

    public void setAktiv(List<CheckType> aktiv) {
        this.aktiv = aktiv != null ? aktiv : List.of();
    }
}
