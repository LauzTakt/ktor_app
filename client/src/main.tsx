import "./i18n";
import { createRoot } from "react-dom/client";
import "maplibre-gl/dist/maplibre-gl.css";
import "./styles.css";
import "./theme";
import { App } from "./App";

const root = createRoot(document.getElementById("root")!);
if (new URLSearchParams(window.location.search).get("replay") === "stettbach") {
  void import("./boarding/StettbachReplay").then(({ StettbachReplay }) => root.render(<StettbachReplay />));
} else {
  root.render(<App />);
}
