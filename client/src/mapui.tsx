// What is drawn on the map (a small legend with hide / remove, clear and undo) and the card for a tapped item.
import { Eye, EyeOff, Layers, MapPin, Navigation, RotateCcw, Trash2, X } from "lucide-react";
import { useEffect, useState } from "react";
import type { Arrival } from "./contract";
import type { DrawnItem } from "./map/MapController";

export type PickRow = { label: string; detail: string; late: boolean; onTap: () => void };
export type PickInfo = { kind: "section" | "station" | "route" | "point" | "train"; title: string; lines: string[]; rows?: PickRow[]; stationId?: string; lon?: number; lat?: number; ask?: string };
export type Tracking = { trainId: string; stopId: string; stationName: string; follow: boolean; arrival: Arrival | null; at: number; gone?: boolean };

/** The train the passenger waits for, live: ETA ticking every second (red under 2 minutes), delay, stops away. */
export function TrackChip({ t, onClose, onFollow }: { t: Tracking; onClose: () => void; onFollow: () => void }) {
  const [, tick] = useState(0);
  useEffect(() => {
    const iv = window.setInterval(() => tick((x) => x + 1), 1000);
    return () => window.clearInterval(iv);
  }, []);
  const a = t.arrival;
  const left = a ? Math.max(0, a.etaSec - (Date.now() - t.at) / 1000) : null;
  const mmss = left == null ? "…" : `${Math.floor(left / 60)}:${String(Math.floor(left % 60)).padStart(2, "0")}`;
  const line = a ? a.line.replace(/^([A-Z]+)(\d)/, "$1 $2") : "Your train";
  return (
    <div className="track" data-soon={left != null && left < 120}>
      <div className="track-main">
        <small>
          {line}
          {a ? ` → ${a.headsign}` : ""}
          {t.stationName ? ` · at ${t.stationName}` : ""}
        </small>
        {t.gone ? (
          <b>Has left the station</b>
        ) : a?.status === "at_station" ? (
          <b>At the station now</b>
        ) : (
          <b>
            Arrives in <span className="track-eta">{mmss}</span>
          </b>
        )}
        {a && (
          <span className="track-meta">
            {a.delaySec >= 60 ? <em>+{Math.round(a.delaySec / 60)} min late</em> : "on time"}
            {a.status !== "at_station" && a.stopsAway > 0 ? ` · ${a.stopsAway} stop${a.stopsAway > 1 ? "s" : ""} away` : ""}
            {a.speedKmh > 0 ? ` · ${Math.round(a.speedKmh)} km/h` : ""}
          </span>
        )}
      </div>
      <button type="button" className="btn btn-ghost btn-circle btn-sm track-btn" data-on={t.follow} aria-pressed={t.follow} onClick={onFollow} aria-label="Follow the train">
        <Navigation size={16} />
      </button>
      <button type="button" className="btn btn-ghost btn-circle btn-sm track-btn" onClick={onClose} aria-label="Stop tracking">
        <X size={16} />
      </button>
    </div>
  );
}

export function MapLegend({ items, onRemove, onToggle, onClear, onUndo }: { items: DrawnItem[]; onRemove: (k: string) => void; onToggle: (k: string) => void; onClear: () => void; onUndo: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="legend" data-open={open}>
      <button type="button" className="btn legend-btn" onClick={() => setOpen((o) => !o)} aria-expanded={open} aria-label="What is on the map">
        <Layers size={17} />
        {items.length > 0 && <b>{items.length}</b>}
      </button>
      {open && (
        <div className="legend-panel">
          <div className="legend-head">
            <span>On the map</span>
            <button type="button" className="btn btn-ghost btn-sm" onClick={onUndo} title="Undo the last change">
              <RotateCcw size={15} /> Undo
            </button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={onClear} disabled={!items.length} title="Clear the map">
              <Trash2 size={15} /> Clear
            </button>
          </div>
          {items.length === 0 && <p className="legend-empty">Nothing drawn yet.</p>}
          <ul>
            {items.map((i) => (
              <li key={i.key} data-hidden={i.hidden}>
                <i style={{ background: i.colour }} />
                <span>
                  <small>{i.kind}</small>
                  {i.label}
                </span>
                <button type="button" onClick={() => onToggle(i.key)} aria-label={i.hidden ? "Show" : "Hide"}>
                  {i.hidden ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
                <button type="button" onClick={() => onRemove(i.key)} aria-label="Remove">
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
        <button type="button" onClick={onClose} aria-label="Close">
          <X size={18} />
        </button>
      </div>
      <div className="pick-body">
        {info.lines.map((l, i) => (
          <p key={i}>{l}</p>
        ))}
      </div>
      {info.rows && info.rows.length > 0 && (
        <ul className="pick-rows">
          {info.rows.map((r, i) => (
            <li key={i}>
              <button type="button" onClick={r.onTap}>
                <span>{r.label}</span>
                <em data-late={r.late}>{r.detail}</em>
                <small>Track</small>
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="pick-actions">
        {place && info.stationId && (
          <>
            <button type="button" className="btn btn-sm" onClick={() => onAction("from")}>
              <MapPin size={15} /> Start here
            </button>
            <button type="button" className="btn btn-sm" onClick={() => onAction("to")}>
              <Navigation size={15} /> Go here
            </button>
          </>
        )}
        {info.ask && (
          <button type="button" className="btn btn-primary btn-sm" onClick={() => onAction("ask")}>
            Ask about this
          </button>
        )}
      </div>
    </div>
  );
}
