// What is drawn on the map (a small legend with hide / remove, clear and undo) and the card for a tapped item.
import { ChevronRight, Clock, ExternalLink, Eye, EyeOff, Layers, MapPin, Navigation, RotateCcw, Trash2, X } from "lucide-react";
import { useEffect, useState } from "react";
import type { Arrival } from "./contract";
import type { DrawnItem } from "./map/MapController";
import { t } from "./i18n";

export type PickRow = { label: string; detail: string; late: boolean; onTap: () => void };
export type PickIncident = { id: string; title: string; severity: string; reason: string[]; since?: string; /** "at least until 12:25" */ end?: string };
export type PickInfo = {
  kind: "section" | "station" | "route" | "point" | "train"; title: string; lines: string[]; rows?: PickRow[]; stationId?: string; lon?: number; lat?: number; ask?: string;
  /** Label of the ask button (default "Ask about this"). */
  askLabel?: string;
  /** Active incidents, tap one for its reason. */
  incidents?: PickIncident[];
  /** Opens the normal passenger view of this item (a train: "on tracks"). */
  onOpen?: () => void;
};
export type Tracking = { trainId: string; stopId: string; stationName: string; follow: boolean; arrival: Arrival | null; at: number; gone?: boolean };

/** The train the passenger waits for, live: ETA ticking every second (red under 2 minutes), delay, stops away. */
export function TrackChip({ t: tr, onClose, onFollow }: { t: Tracking; onClose: () => void; onFollow: () => void }) {
  const [, tick] = useState(0);
  useEffect(() => {
    const iv = window.setInterval(() => tick((x) => x + 1), 1000);
    return () => window.clearInterval(iv);
  }, []);
  const a = tr.arrival;
  const left = a ? Math.max(0, a.etaSec - (Date.now() - tr.at) / 1000) : null;
  const mmss = left == null ? "…" : `${Math.floor(left / 60)}:${String(Math.floor(left % 60)).padStart(2, "0")}`;
  const line = a ? a.line.replace(/^([A-Z]+)(\d)/, "$1 $2") : t("Your train");
  return (
    <div className="track" data-soon={left != null && left < 120}>
      <div className="track-main">
        <small>
          {line}
          {a ? ` → ${a.headsign}` : ""}
          {tr.stationName ? ` · ${t("at {station}", { station: tr.stationName })}` : ""}
        </small>
        {tr.gone ? (
          <b>{t("Has left the station")}</b>
        ) : a?.status === "at_station" ? (
          <b>{t("At the station now")}</b>
        ) : (
          <b>
            {t("Arrives in")} <span className="track-eta">{mmss}</span>
          </b>
        )}
        {a && (
          <span className="track-meta">
            {a.delaySec >= 60 ? <em>{t("+{n} min late", { n: Math.round(a.delaySec / 60) })}</em> : t("on time")}
            {a.status !== "at_station" && a.stopsAway > 0 ? ` · ${t(a.stopsAway > 1 ? "{n} stops away" : "{n} stop away", { n: a.stopsAway })}` : ""}
            {a.speedKmh > 0 ? ` · ${Math.round(a.speedKmh)} km/h` : ""}
          </span>
        )}
      </div>
      <button type="button" className="btn btn-ghost btn-circle btn-sm track-btn" data-on={tr.follow} aria-pressed={tr.follow} onClick={onFollow} aria-label={t("Follow the train")}>
        <Navigation size={16} />
      </button>
      <button type="button" className="btn btn-ghost btn-circle btn-sm track-btn" onClick={onClose} aria-label={t("Stop tracking")}>
        <X size={16} />
      </button>
    </div>
  );
}

export function MapLegend({ items, onRemove, onToggle, onClear, onUndo }: { items: DrawnItem[]; onRemove: (k: string) => void; onToggle: (k: string) => void; onClear: () => void; onUndo: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="legend" data-open={open}>
      <button type="button" className="btn legend-btn" onClick={() => setOpen((o) => !o)} aria-expanded={open} aria-label={t("What is on the map")}>
        <Layers size={17} />
        {items.length > 0 && <b>{items.length}</b>}
      </button>
      {open && (
        <div className="legend-panel">
          <div className="legend-head">
            <span>{t("On the map")}</span>
            <button type="button" className="btn btn-ghost btn-sm" onClick={onUndo} title={t("Undo the last change")}>
              <RotateCcw size={15} /> {t("Undo")}
            </button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={onClear} disabled={!items.length} title={t("Clear the map")}>
              <Trash2 size={15} /> {t("Clear")}
            </button>
          </div>
          {items.length === 0 && <p className="legend-empty">{t("Nothing drawn yet.")}</p>}
          <ul>
            {items.map((i) => (
              <li key={i.key} data-hidden={i.hidden}>
                <i style={{ background: i.colour }} />
                <span>
                  <small>{i.kind}</small>
                  {i.label}
                </span>
                <button type="button" onClick={() => onToggle(i.key)} aria-label={i.hidden ? t("Show") : t("Hide")}>
                  {i.hidden ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
                <button type="button" onClick={() => onRemove(i.key)} aria-label={t("Remove")}>
                  <X size={16} />
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

export function PickCard({ info, onClose, onAction }: { info: PickInfo; onClose: () => void; onAction: (a: "ask" | "from" | "to") => void }) {
  const place = info.kind === "station" || info.kind === "point";
  return (
    <div className="pick" role="dialog" aria-label={info.title}>
      <div className="pick-head">
        <strong>{info.title}</strong>
        <button type="button" onClick={onClose} aria-label={t("Close")}>
          <X size={18} />
        </button>
      </div>
      <div className="pick-body">
        {info.lines.map((l, i) => (
          <p key={i}>{l}</p>
        ))}
      </div>
      {info.incidents && info.incidents.length > 0 && (
        <div className="pick-incidents">
          {info.incidents.map((i) => (
            <details key={i.id} className={`pick-inc sev-${i.severity}`}>
              <summary>
                <span className="dot" aria-hidden="true" />
                <span className="t">
                  {i.title}
                  {i.end && (
                    <span className="end">
                      <Clock size={12} aria-hidden="true" /> {i.end}
                    </span>
                  )}
                </span>
                <ChevronRight size={16} className="more" aria-hidden="true" />
              </summary>
              <div className="why">
                {(i.reason.length ? i.reason : [t("No reason given yet.")]).map((l, k) => <p key={k}>{l}</p>)}
                {i.since && <p className="since">{t("Since {time}", { time: i.since })}</p>}
              </div>
            </details>
          ))}
        </div>
      )}
      {info.rows && info.rows.length > 0 && (
        <ul className="pick-rows">
          {info.rows.map((r, i) => (
            <li key={i}>
              <button type="button" onClick={r.onTap}>
                <span>{r.label}</span>
                <em data-late={r.late}>{r.detail}</em>
                <small>{t("Track")}</small>
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="pick-actions">
        {place && info.stationId && (
          <>
            <button type="button" className="btn btn-sm" onClick={() => onAction("from")}>
              <MapPin size={15} /> {t("Start here")}
            </button>
            <button type="button" className="btn btn-sm" onClick={() => onAction("to")}>
              <Navigation size={15} /> {t("Go here")}
            </button>
          </>
        )}
        {info.onOpen && (
          <button type="button" className="btn btn-sm" onClick={info.onOpen}>
            <ExternalLink size={15} /> {t("Open train view")}
          </button>
        )}
        {info.ask && (
          <button type="button" className="btn btn-primary btn-sm" onClick={() => onAction("ask")}>
            {info.askLabel ?? t("Ask about this")}
          </button>
        )}
      </div>
    </div>
  );
}
