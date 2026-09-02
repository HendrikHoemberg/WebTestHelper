package dev.hendrikhoemberg.webtesthelper.findings;

/**
 * Open (currently observable, not silenced) findings of one site, counted per severity, with
 * the untriaged share counted per severity too so a single grouped statement produces both
 * without a second round-trip. A site with no open findings is absent from the caller's map,
 * not present with zeros — the dashboard must still render a tile for a site that never ran.
 */
public record OpenFindingCounts(
        int errors,
        int warnings,
        int infos,
        int untriaged,
        int untriagedErrors,
        int untriagedWarnings,
        int acknowledged) {

    public OpenFindingCounts(int errors, int warnings, int infos, int untriaged) {
        this(errors, warnings, infos, untriaged, errors, warnings, 0);
    }

    public static OpenFindingCounts none() {
        return new OpenFindingCounts(0, 0, 0, 0, 0, 0, 0);
    }

    public int total() {
        return errors + warnings + infos;
    }
}
