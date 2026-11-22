import { ArrowLeft, ArrowRight, Check, MapPin } from "lucide-react";
import { useId, type Ref } from "react";
import { hhmm } from "../format";

export type BoardingPoint = {
  label: string;
  coordinates: [longitude: number, latitude: number];
  landmark: string;
  directions: string;
};

export type BoardingService = {
  line: string;
  number?: string;
  direction: string;
  departure: number;
  from: string;
  destination: string;
  point: BoardingPoint;
};

export function BoardingCard({ service, focused, status, action = "Board this one", onToggle, headingRef }: {
  service: BoardingService;
  focused: boolean;
  status: string;
  action?: string;
  onToggle: () => void;
  headingRef?: Ref<HTMLHeadingElement>;
}) {
  const heading = useId();
  const directions = useId();
  return (
    <article className="card boarding-card" aria-labelledby={heading}>
      <div className="boarding-departure">
        <time dateTime={new Date(service.departure).toISOString()}>{hhmm(service.departure)}</time>
        <div>
          <h2 id={heading} ref={headingRef} tabIndex={-1}>{service.line} <span>toward {service.direction}</span></h2>
          <p>{service.from}{service.number && ` · Train ${service.number}`}</p>
        </div>
      </div>
      <p className="boarding-confirmation"><Check size={18} aria-hidden="true" /> Stops at {service.destination}</p>
      <div className="boarding-wayfinding" id={directions}>
        <MapPin size={20} aria-hidden="true" />
        <div>
          <strong>{service.point.label}</strong>
          <p>{focused ? service.point.directions : service.point.landmark}</p>
        </div>
      </div>
      <p className="boarding-status" role="status">{status}</p>
      <button
        type="button"
        className="boarding-action"
        data-secondary={focused}
        aria-expanded={focused}
        aria-controls={directions}
        onClick={onToggle}
      >
        {focused && <ArrowLeft size={18} aria-hidden="true" />}
        {focused ? "Back to rail map" : action}
        {!focused && <ArrowRight size={18} aria-hidden="true" />}
      </button>
    </article>
  );
}
